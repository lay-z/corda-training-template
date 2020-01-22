package net.corda.training.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.AMOUNT
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.utils.sumCash
import net.corda.training.state.IOUState
import java.lang.IllegalArgumentException

/**
 * This is where you'll add the contract code which defines how the [IOUState] behaves. Look at the unit tests in
 * [IOUContractTests] for instructions on how to complete the [IOUContract] class.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.training.contract.IOUContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        // Add commands here.
        // E.g
        // class DoSomething : TypeOnlyCommandData(), Commands
        class Issue : TypeOnlyCommandData(), Commands

        class Transfer : TypeOnlyCommandData(), Commands

        class Settle : TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        // Add contract code here.
        val command = tx.commands.requireSingleCommand<Commands>()


        when (command.value) {
            is Commands.Issue ->
                requireThat {
                    "No inputs should be consumed when issuing an IOU." using (tx.inputs.size == 0)
                    "Only one output state should be created when issuing an IOU." using (tx.outputs.size == 1)
                    val outputState = tx.outputsOfType<IOUState>().single()
                    "A newly issued IOU must have a positive amount." using (outputState.amount.quantity > 0)
                    "The lender and borrower cannot have the same identity." using (outputState.lender != outputState.borrower)

                    "Both lender and borrower together only may sign IOU issue transaction." using (command.signers.toSet() == outputState.participants.map { it.owningKey }.toSet())
                }
            is Commands.Transfer ->
                requireThat {
                    "An IOU transfer transaction should only consume one input state." using (tx.inputs.size == 1)
                    "An IOU transfer transaction should only create one output state." using (tx.outputs.size == 1)
                    val inputState = tx.inputsOfType<IOUState>().single()
                    val outputState = tx.outputsOfType<IOUState>().single()

                    "Only the lender property may change." using (outputState == inputState.copy(lender = outputState.lender))
                    "The lender property must change in a transfer." using (outputState.lender != inputState.lender)
                    "The borrower, old lender and new lender only must sign an IOU transfer transaction" using (
                            (outputState.participants + inputState.participants).map { it.owningKey }.toSet() == command.signers.toSet()
                            )
                }
            is Commands.Settle ->
                requireThat {
                    val states = tx.groupStates<IOUState, UniqueIdentifier> { it.linearId }
                    val groupedState = states.single()
                    // There should be one input object
                    "There must be one input IOU." using (groupedState.inputs.size == 1)

                    val outputCash = tx.outputsOfType<Cash.State>()
                    val inputIOU = groupedState.inputs.single()

                    "There must be output cash." using (outputCash.size > 0)

                    val cashToLender = outputCash.filter { it.owner == inputIOU.lender }

                    "Output cash must be paid to the lender." using (cashToLender.size > 0)

                    val totalCash = outputCash.sumCash().withoutIssuer()

                    "The amount settled cannot be more than the amount outstanding." using (totalCash <= inputIOU.amount - inputIOU.paid)

                    if (totalCash == inputIOU.amount - inputIOU.paid) {
                        "There must be no output IOU as it has been fully settled." using (groupedState.outputs.size == 0)
                    } else {
                        "There must be one output IOU." using (groupedState.outputs.size == 1)

                        val outputIOU = groupedState.outputs.single()
                        "Only the paid amount can change." using (outputIOU == inputIOU.copy(paid = outputIOU.paid))
                    }
                    "Both lender and borrower together only must sign the IOU settle transaction." using (command.signers.toSet() == inputIOU.participants.map { it.owningKey }.toSet())
                }
        }
    }
}