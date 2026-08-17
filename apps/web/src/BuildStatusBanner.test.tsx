import { render, screen } from "@testing-library/react";
import { BuildStatusBanner } from "./BuildStatusBanner";

test("always identifies the build as baseline and Production BLOCKED", () => {
  render(<BuildStatusBanner/>);
  expect(screen.getByRole("status", { name: "build information" })).toHaveTextContent(/baseline · Production BLOCKED/);
});
