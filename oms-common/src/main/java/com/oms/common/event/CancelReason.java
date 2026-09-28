package com.oms.common.event;

/** Why the matching engine removed residual quantity from a book. */
public enum CancelReason {

    /** A client cancel request was matched to a live order. */
    USER_REQUEST,

    /** IOC order: the part that could not trade immediately. */
    IOC_RESIDUAL,

    /** FOK order: could not be filled in full, so none of it traded. */
    FOK_UNFILLABLE,

    /** Cancel requested for an order the engine does not have (already done, or never seen). */
    UNKNOWN_ORDER,

    /** Book was flushed, e.g. end of session or operator action. */
    SESSION_END
}
