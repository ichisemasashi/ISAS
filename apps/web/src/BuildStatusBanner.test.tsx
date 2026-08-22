import { render, screen } from "@testing-library/react";
import { BuildStatusBanner } from "./BuildStatusBanner";

test("always identifies the build as baseline and Production BLOCKED", () => {
  render(<BuildStatusBanner/>);
  expect(screen.getByRole("status", { name: "build information" })).toHaveTextContent(/baseline · Production BLOCKED/);
  expect(screen.getByRole("status", { name: "build information" })).toHaveTextContent(/圃場・指示・日誌・農薬・在庫のself-host／offline core/);
  expect(screen.getByRole("status", { name: "build information" })).toHaveTextContent(/KSAS同等ではありません/);
});
