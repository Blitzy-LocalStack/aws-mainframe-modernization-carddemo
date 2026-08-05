import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Assumptions: Testing Library renders into document.body, so cleaning after
// every test prevents a previous route or alert from satisfying the next
// assertion by accident.
afterEach(cleanup);
