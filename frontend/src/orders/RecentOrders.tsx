import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import ConfirmationNumberRounded from "@mui/icons-material/ConfirmationNumberRounded";
import { Link as RouterLink } from "react-router-dom";
import { getRecentOrders } from "../sale/storage";
import { EmptyState } from "../ui/EmptyState";

/**
 * Every ticket bought in this browser, across sales (FE_SPEC §3). The receipt token is a bearer
 * capability: it appears only inside a link the buyer chooses to open, never as text (FE_SPEC §3.1).
 */
export function RecentOrders() {
  const orders = getRecentOrders();

  if (orders.length === 0) {
    return (
      <EmptyState icon={<ConfirmationNumberRounded sx={{ fontSize: 40 }} />} title="No tickets yet">
        Tickets you buy in this browser show up here, so you can always get back to them.
      </EmptyState>
    );
  }

  return (
    <Stack spacing={1.5} component="ul" sx={{ listStyle: "none", p: 0, m: 0 }} aria-label="Your tickets">
      {orders.map((order) => (
        <Card component="li" key={order.orderNumber}>
          <CardContent sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap", "&:last-child": { pb: 2 } }}>
            <ConfirmationNumberRounded color="primary" aria-hidden />
            <Stack sx={{ flex: 1, minWidth: 160 }}>
              <Typography fontWeight={700} dir="auto">
                {order.eventTitle}
              </Typography>
              <Typography variant="body2" color="text.secondary" className="tabular">
                Order {order.orderNumber}
              </Typography>
            </Stack>
            <Button
              component={RouterLink}
              to={`/orders/${encodeURIComponent(order.orderNumber)}?receiptToken=${encodeURIComponent(order.receiptToken)}`}
              variant="outlined"
            >
              View tickets
            </Button>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}
