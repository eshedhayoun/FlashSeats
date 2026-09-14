import { createTheme } from "@mui/material/styles";

export const appTheme = createTheme({
  palette: {
    mode: "dark",
    primary: { main: "#65d391", contrastText: "#102017" },
    secondary: { main: "#9be7b2" },
    background: { default: "#252a27", paper: "#303732" },
    text: { primary: "#b9f2c8", secondary: "#a8c5b1" },
    divider: "#4a5d50"
  },
  typography: {
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
  }
});
