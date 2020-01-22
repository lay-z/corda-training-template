package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.finance.workflows.asset.CashUtils
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!
        val notary = serviceHub.networkMapCache.notaryIdentities.single()
        val me = serviceHub.myInfo.legalIdentities.first()
//        val other = serviceHub.
        val unsignedTransaction = TransactionBuilder(notary = notary)
        val issueCommand = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })

        unsignedTransaction.addCommand(issueCommand)
        unsignedTransaction.addOutputState(state)
        unsignedTransaction.verify(serviceHub)

        val privateSignedTransaction = serviceHub.signInitialTransaction(
                unsignedTransaction
        )

        val flows = state.participants.filter { it != me }.map { initiateFlow(it) }

        val signedTransactions = subFlow(CollectSignaturesFlow(privateSignedTransaction, flows))

        val notarizedTransaction = subFlow(FinalityFlow(signedTransactions, flows))

        return notarizedTransaction
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                // Check that the transaction is really an issue transaction
                assert(stx.tx.outputs.single().data is IOUState)
            }
        }
        subFlow(signedTransactionFlow)
        subFlow(ReceiveFinalityFlow(flowSession))
    }
}