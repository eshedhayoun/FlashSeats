export type TimerZeroDecision = "complete-payment" | "rehydrate";

export function decideTimerZero(paymentInFlight: boolean): TimerZeroDecision {
  return paymentInFlight ? "complete-payment" : "rehydrate";
}
