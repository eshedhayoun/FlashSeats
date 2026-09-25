import Button from "@mui/material/Button";
import { useNavigate } from "react-router-dom";

export function HomeButton() {
  const navigate = useNavigate();

  return (
    <Button variant="text" onClick={() => navigate("/")}>
      Home
    </Button>
  );
}
