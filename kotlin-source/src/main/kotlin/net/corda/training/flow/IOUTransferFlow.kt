package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import org.checkerframework.common.aliasing.qual.Unique
import java.lang.IllegalArgumentException

/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val me = serviceHub.myInfo.legalIdentities.first()
        val unsignedTransaction = TransactionBuilder(notary=notary)
        // Fetech the IOUState from vault
        val iouQuery = serviceHub.vaultService.queryBy(IOUState::class.java, criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId)))

        val iouState = iouQuery.states.first()
        val iou = iouState.state.data

        if (iou.lender != me) {
            throw IllegalArgumentException("Requester has to be lender, cannot be any other party")
        }

        val issueCommand = Command(IOUContract.Commands.Transfer(), listOf(iou.lender.owningKey, iou.borrower.owningKey, newLender.owningKey))

        unsignedTransaction.addCommand(issueCommand)
        unsignedTransaction.addOutputState(iou.withNewLender(newLender))
        unsignedTransaction.addInputState(iouState)


        unsignedTransaction.verify(serviceHub)

        val privateSignedTransaction = serviceHub.signInitialTransaction(
                unsignedTransaction
        )

        val flows = (listOf(newLender) + iou.participants).filter { it != me }.map { initiateFlow(it) }

        val signedTransactions = subFlow(CollectSignaturesFlow(privateSignedTransaction, flows))

        return subFlow(FinalityFlow(signedTransactions, flows))
    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }

        subFlow(signedTransactionFlow)
        subFlow(ReceiveFinalityFlow(flowSession))
    }
}