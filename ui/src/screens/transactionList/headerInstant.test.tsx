/**
 * @file Proves the transaction browse publishes the SERVER-anchored paint instant to the shell.
 *
 * Purpose
 * -------
 * `ScreenHeader` takes its date and time from an optional instant and falls back to the browser clock
 * when a screen omits it -- a fallback that module registers as divergence D-7, because the source read
 * one clock for every terminal (`POPULATE-HEADER-INFO` at `app/cbl/COTRN00C.cbl` L567 moves
 * `FUNCTION CURRENT-DATE` at L569, performed at L529 before the `SEND MAP` that paints a page). This
 * screen's shell delegation omitted the instant, so the band painted the workstation's clock and two
 * operators reading one browse across a midnight boundary could read two different dates. This file is
 * the assertion that the delegation carries the instant.
 *
 * Assumptions: the property is asserted through the RENDERED band rather than by inspecting the
 * published slot object, because the slot is a module-scoped store with no read API of its own and
 * because the band is where the divergence is visible. The screen is therefore mounted inside `AppShell`
 * exactly as `ui/src/router.tsx` mounts it -- rendered outside the shell it would publish a delegation
 * nothing subscribes to and no band would exist to assert against.
 *
 * Assumptions: the expected text is FORMATTED from the anchored instant with the band's own exported
 * format rather than written as a literal, so the case states "the band shows the server's instant"
 * rather than "the band shows 07/18/22" -- which would also have to be rewritten in whichever local zone
 * the runner happens to be configured for.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record: `ui/eslint.config.js` requires a documentation block on a
 * function expression in any position, and Prettier moves a block comment that follows an argument comma
 * onto the preceding literal, which detaches it from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted.
import { render, screen, waitFor, within } from '@testing-library/react';
import dayjs from 'dayjs';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { listTransactions } from '../../api/transactions';
import type { PageResponse, TransactionSummary } from '../../api/types';
import { recordServerDate, resetServerClock } from '../../api/serverClock';
import { AppShell } from '../../layout/AppShell';
import { HEADER_DATE_FORMAT } from '../../layout/ScreenHeader';
import { APP_TITLE_DISPLAY } from '../../messages/messages';
import TransactionListScreen, {
  TRANSACTION_LIST_PROGRAM_NAME,
  TRANSACTION_LIST_TRANSACTION_ID,
} from './index';

/**
 * Builds the mocked surface of the transaction transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 *
 * Assumptions: the browse operation alone is stubbed, which is the only operation this screen calls; the
 * transport module is mocked rather than the client beneath it because nothing here asserts a request.
 * @returns {Record<string, unknown>} The browse operation as a fresh spy.
 */
function mockTransactionTransportModule(): Record<string, unknown> {
  return { listTransactions: vi.fn() };
}

vi.mock('../../api/transactions', mockTransactionTransportModule);

/**
 * A server instant deliberately far from any date a runner's own clock can hold.
 *
 * Assumptions: an HTTP-date string, because that is the form `recordServerDate` parses -- it anchors from
 * a response's `Date` header. The value is the baseline's own business date from
 * `app/jcl/INTCALC.jcl` L22, which keeps the fixture recognisable, and being years in the past it can
 * never coincide with the browser reading this case has to distinguish it from.
 */
const SERVER_DATE_HEADER = 'Mon, 18 Jul 2022 22:10:31 GMT';

/** One browse row, carrying the four members the contract publishes. */
const ROW: TransactionSummary = {
  transactionId: '0000000000000001',
  originTimestamp: '2022-07-18 22:10:31.000000',
  description: 'PARITY TRANSACTION',
  amount: '100.00',
};

/** One populated page with no further page in either direction. */
const ONE_ROW_PAGE: PageResponse<TransactionSummary> = {
  items: [ROW],
  firstKey: null,
  lastKey: null,
  hasNext: false,
};

/**
 * Discards the anchor and the transport stub so neither leaks into a later case or file.
 *
 * Assumptions: the clock anchor is module-scoped state, so a case that did not clear it would decide the
 * outcome of any later case asserting the unanchored fallback.
 * @returns {void} Nothing; the anchor and the spy are reset.
 */
function resetAnchorAndTransport(): void {
  resetServerClock();
  vi.mocked(listTransactions).mockReset();
}

/**
 * Asserts the band renders the anchored server instant and not the browser's own date.
 *
 * Purpose: this is the case the omitted delegation cannot pass. With no instant published, `ScreenHeader`
 * formats `dayjs()` -- today, on the machine running the assertion -- so the first expectation fails on
 * the anchored date being absent and the second fails on the browser date being present.
 *
 * Assumptions: the DATE slot is asserted rather than the time slot. `serverInstant` reports the anchor
 * plus the elapsed time since it was recorded, which advances by milliseconds between the render and the
 * query, so the seconds in the time slot are not a stable expectation while the date is.
 *
 * ⚠️ Assumptions: both queries are scoped to the header REGION and not to the document, and the scoping
 * is load-bearing rather than tidiness. This browse also renders each row's origin instant, and the
 * fixture row's is deliberately the same moment as the anchor -- so an unscoped query for the anchored
 * date matches the band AND the row, which is a multiple-match error rather than a pass, and an unscoped
 * query for the browser date would be answered by any row that happened to carry today's.
 * @returns {Promise<void>} Resolves once the first page has settled and the band has been read.
 */
async function paintsTheServerInstantInTheHeaderBand(): Promise<void> {
  vi.mocked(listTransactions).mockResolvedValue(ONE_ROW_PAGE);
  expect(recordServerDate(SERVER_DATE_HEADER)).toBe(true);

  render(
    <MemoryRouter initialEntries={['/transactions']}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/transactions" element={<TransactionListScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  await waitFor(
    /**
     * Waits for the band to identify this screen, which is what proves the delegation arrived.
     * @returns {void} Nothing; throws until both header slots are present.
     */
    () => {
      expect(screen.getByText(TRANSACTION_LIST_TRANSACTION_ID)).toBeInTheDocument();
      expect(screen.getByText(TRANSACTION_LIST_PROGRAM_NAME)).toBeInTheDocument();
    },
  );

  const anchored = dayjs(SERVER_DATE_HEADER).format(HEADER_DATE_FORMAT);
  const browserReading = dayjs().format(HEADER_DATE_FORMAT);

  expect(
    anchored,
    'the fixture must differ from the browser reading, or the case asserts nothing',
  ).not.toBe(browserReading);

  const band = within(screen.getByRole('region', { name: APP_TITLE_DISPLAY }));
  expect(band.getByText(anchored)).toBeInTheDocument();
  expect(band.queryByText(browserReading)).not.toBeInTheDocument();
}

/**
 * Groups the header-instant case for this screen.
 * @returns {void} Nothing; the case is registered with the runner.
 */
function transactionBrowseHeaderInstant(): void {
  afterEach(resetAnchorAndTransport);
  it('paints the server instant in the header band', paintsTheServerInstantInTheHeaderBand);
}

describe('transaction browse header instant', transactionBrowseHeaderInstant);
