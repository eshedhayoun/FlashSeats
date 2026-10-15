import Container from "@mui/material/Container";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { admitQueue, leaveQueue } from "../api/endpoints";
import { asApiError, isBackPressure } from "../api/errors";
import type { OrderReceipt } from "../api/types";
import { backoffMs } from "../api/client";
import { CheckoutView } from "../checkout/CheckoutView";
import { LandingView } from "../landing/LandingView";
import { useEvent } from "../landing/useEvent";
import { NotFoundPage } from "../notfound/NotFoundPage";
import { QueueView } from "../queue/QueueView";
import { SeatSelectionView } from "../selection/SeatSelectionView";
import { TerminalView } from "../terminal/TerminalView";
import { getSaleValue, rememberOrder, setAdmissionToken, setSaleValue } from "./storage";
import { ErrorState } from "../ui/ErrorState";
import { LoadingState } from "../ui/LoadingState";
import { Notice } from "../ui/Notice";
import { noticeForTransition, type SaleNoticeKind, type SaleView } from "./notices";
import { SaleNoticeBanner } from "./SaleNoticeBanner";
import { DegradedView, OpeningOrderView, TurnView } from "./StatusViews";
import { useSaleState } from "./useSaleState";

/** "It's your turn" stays up at least this long: an instant flip reads as an error (FE_SPEC V2). */
const TURN_MIN_MS = 600;
/** Transient failures exchanging a pass are retried this many times before the pass is given up on. */
const ADMIT_RETRIES = 3;

const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));

/**
 * One sale, rendered from the server's state for it (FE_SPEC §1). The view is `routeFor(state)`, never
 * navigation history, so a reload, Back or a second tab lands where the server says the buyer is.
 */
export function EventPage({ eventId }: { eventId: number }) {
  const [params] = useSearchParams();
  const buyMore = params.get("buyMore") === "1";
  const navigate = useNavigate();
  const event = useEvent(eventId);
  const sale = useSaleState(eventId, { buyMore });
  const route = sale.data?.route ?? null;
  const refresh = sale.refresh;

  // ---- what just happened, said on the next screen --------------------------------------------
  const [notice, setNotice] = useState<{ kind: SaleNoticeKind; orderNumber?: string } | null>(null);
  const pendingNotice = useRef<SaleNoticeKind | null>(null);
  // Remembered across a reload, so "your reservation ended while you were away" survives one
  // (FE_SPEC §3, "V4, hold expired while away").
  const previousView = useRef<SaleView | null>(getSaleValue(eventId, "view") as SaleView | null);

  useEffect(() => {
    if (!sale.data) return;
    const previous = previousView.current;
    const next = sale.data.route.view;
    previousView.current = next;
    setSaleValue(eventId, "view", next);
    if (previous === next && pendingNotice.current === null) return;
    const kind = pendingNotice.current ?? (previous ? noticeForTransition(previous, sale.data) : null);
    pendingNotice.current = null;
    setNotice(kind ? { kind, orderNumber: sale.data.state.order?.orderNumber } : null);
  }, [sale.data, eventId]);

  /** A buyer's own action explains the next screen better than any guess from the state change. */
  const refreshExplaining = useCallback(
    async (kind: SaleNoticeKind) => {
      pendingNotice.current = kind;
      await refresh();
    },
    [refresh]
  );

  // ---- the turn: exchange the pass for an admission -------------------------------------------
  const failedPasses = useRef(new Set<string>());
  const admittingPass = useRef<string | null>(null);
  const mounted = useRef(true);
  const [admitting, setAdmitting] = useState(false);
  const passToken = route?.view === "promoted" ? route.passToken : null;
  const passFailed = passToken !== null && failedPasses.current.has(passToken);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    // Deduplicated by pass, not by render: the exchange is single-use, so a second attempt with the
    // same pass could only fail — and in development React mounts every effect twice.
    if (!passToken || failedPasses.current.has(passToken) || admittingPass.current === passToken) return;
    admittingPass.current = passToken;
    setAdmitting(true);
    const shownAt = Date.now();

    void (async () => {
      for (let attempt = 0; ; attempt++) {
        try {
          const admission = await admitQueue({ eventId }, passToken);
          setAdmissionToken(eventId, admission.admissionToken);
          await sleep(Math.max(0, TURN_MIN_MS - (Date.now() - shownAt)));
          break;
        } catch (cause) {
          const error = asApiError(cause);
          if (isBackPressure(error) && attempt < ADMIT_RETRIES) {
            await sleep(backoffMs(error, attempt + 1));
            continue;
          }
          // Not retried in place. Another tab of this buyer may have spent the pass, and the refresh
          // below says so; a pass the server still offers after failing is never tried again, so a
          // bad pass cannot loop between here and the router (FE_SPEC §3, U-5).
          failedPasses.current.add(passToken);
          break;
        }
      }
      if (!mounted.current) return;
      await refresh();
      if (mounted.current) setAdmitting(false);
    })();
  }, [passToken, eventId, refresh]);

  // ---- a completed purchase lives at its own address ------------------------------------------
  useEffect(() => {
    if (route?.view === "confirmation") {
      navigate(`/orders/${encodeURIComponent(route.orderNumber)}`, { replace: true });
    }
  }, [navigate, route]);

  const completed = useCallback(
    (receipt: OrderReceipt) => {
      rememberOrder(receipt, event.event?.title ?? "FlashSeats event");
      // Replace, so Back from the receipt goes where the buyer came from, not into a page that
      // would only redirect to the receipt again. No receipt token in this URL (FE_SPEC §3.1).
      navigate(`/orders/${encodeURIComponent(receipt.orderNumber)}`, { replace: true });
    },
    [event.event?.title, navigate]
  );

  // ---- rendering -------------------------------------------------------------------------------
  const notFound = sale.error?.code === "EVENT_NOT_FOUND" || event.error?.code === "EVENT_NOT_FOUND";
  if (notFound) return <NotFoundPage what="event" />;

  if (!sale.data || !event.event) {
    const failure = sale.data ? event.error : sale.error ?? event.error;
    if (failure) {
      return (
        <Container maxWidth="sm">
          <ErrorState
            error={failure}
            onRetry={() => {
              void refresh();
              void event.reload();
            }}
          />
        </Container>
      );
    }
    return (
      <Container maxWidth="md">
        <LoadingState shape="event" label="Loading the sale" />
      </Container>
    );
  }

  const state = sale.data.state;
  const view = route!.view;
  // A turn that could not be used shows the landing view, with the reason and a way back in.
  const shownNotice = view === "promoted" && passFailed ? { kind: "PASS_FAILED" as const } : notice;
  const banner = (
    <>
      {sale.error && (
        <Notice severity="warning" title="We're having trouble reaching the sale" testId="stale">
          What you see may be a few seconds old. We're retrying — nothing you hold is affected.
        </Notice>
      )}
      {shownNotice && (
        <SaleNoticeBanner
          kind={shownNotice.kind}
          view={passFailed ? "landing" : view}
          orderNumber={"orderNumber" in shownNotice ? shownNotice.orderNumber : undefined}
          onClose={() => setNotice(null)}
        />
      )}
    </>
  );

  if (view === "promoted" && !passFailed) return <TurnView />;
  if (admitting) return <TurnView />;

  switch (route!.view) {
    case "queue":
      return (
        <QueueView
          event={event.event}
          queue={route!.queue}
          windowStatus={state.windowStatus}
          notice={banner}
          onRefresh={refresh}
          onLeft={() => refreshExplaining("LEFT_QUEUE")}
        />
      );
    case "select":
      return (
        <SeatSelectionView
          event={event.event}
          admissionExpiresAt={route!.admissionExpiresAt}
          windowStatus={state.windowStatus}
          notice={banner}
          onRefresh={refresh}
          onReloadEvent={event.reload}
          onAdmissionLost={() => refreshExplaining("ADMISSION_ENDED")}
        />
      );
    case "checkout":
      return (
        <CheckoutView
          key={route!.hold.holdToken}
          event={event.event}
          hold={route!.hold}
          windowStatus={state.windowStatus}
          notice={banner}
          onRefresh={refresh}
          onCompleted={completed}
          onEnded={refreshExplaining}
          onReleased={() => refreshExplaining("RELEASED")}
        />
      );
    case "terminal":
      return <TerminalView reason={route!.reason} event={event.event} notice={banner} onRefresh={refresh} />;
    case "degraded":
      return <DegradedView onRefresh={refresh} />;
    case "confirmation":
      return <OpeningOrderView />;
    case "landing":
    case "promoted":
    default:
      return (
        <LandingView
          event={event.event}
          windowStatus={state.windowStatus}
          notice={banner}
          // A broken pass outranks a fresh place in line until it expires; leaving drops it first.
          beforeJoin={passFailed ? () => leaveQueue(eventId) : undefined}
          onJoined={refresh}
          onEventStale={() => void event.reload()}
        />
      );
  }
}
