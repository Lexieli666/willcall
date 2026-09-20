package dev.willcall.payment;

/** Outcome of a charge attempt. */
public record PaymentResult(Status status, String reference, String failureCode) {

  public enum Status {
    /** Money moved. Safe to confirm. */
    SUCCEEDED,
    /** The gateway said no. The seats go back. */
    DECLINED,
    /**
     * No answer within the deadline. <b>The charge may still have happened.</b> The order is left
     * PENDING and the seats stay held until the hold expires, because releasing them would let
     * somebody else buy a seat the buyer may already have paid for.
     */
    TIMED_OUT
  }

  public boolean succeeded() {
    return status == Status.SUCCEEDED;
  }
}
