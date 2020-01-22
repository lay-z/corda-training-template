package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.workflows.asset.CashUtils
import net.corda.finance.workflows.getCashBalance
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import java.util.*

/**
 * This is the flow which handles the (partial) settlement of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUSettleFlow(val linearId: UniqueIdentifier, val amount: Amount<Currency>) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val me = serviceHub.myInfo.legalIdentities.first()
        val iouState = serviceHub.vaultService.queryBy(
                IOUState::class.java,
                QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        ).states.first()

        val iou = iouState.state.data

        if (iou.borrower != me) {
            throw IllegalArgumentException("Expected initiating node to be owned by borrower.")
        }

        val balance = serviceHub.getCashBalance(iou.amount.token)

        if (balance.quantity == 0L) {
            throw IllegalArgumentException("""Borrower has no ${iou.amount.token} to settle.""")
        }
        if (balance < amount) {
            throw IllegalArgumentException("Borrower has only ${balance} but needs ${amount} to settle.")
        }

        val utx = TransactionBuilder(notary = notary)
                .addCommand(IOUContract.Commands.Settle(), iou.participants.map { it.owningKey })
                .addOutputState(iou.pay(amount))
                .addInputState(iouState)

        val (withCashTx, _) = CashUtils.generateSpend(serviceHub, utx, amount, serviceHub.myInfo.legalIdentitiesAndCerts.first(), iou.lender)

        withCashTx.verify(serviceHub)

        val ptx = serviceHub.signInitialTransaction(withCashTx)

        val flows = iou.participants.filter { it != me }.map { initiateFlow(it) }

        val signed = subFlow(CollectSignaturesFlow(ptx, flows))
        return subFlow(FinalityFlow(signed, flows))
    }
}

/**
 * This is the flow which signs IOU settlements.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUSettleFlow::class)
class IOUSettleFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val outputStates = stx.tx.outputs.map { it.data::class.java.name }.toList()
                "There must be an IOU transaction." using (outputStates.contains(IOUState::class.java.name))
            }
        }

        subFlow(signedTransactionFlow)
        subFlow(ReceiveFinalityFlow(flowSession))
    }
}

@InitiatingFlow
@StartableByRPC
/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}