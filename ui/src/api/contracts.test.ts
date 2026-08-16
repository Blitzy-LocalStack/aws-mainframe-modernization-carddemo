/// <reference types="node" />

/**
 * @file The contract-drift gate: the one build step that compares this SPA's typed API client layer
 * with the service-held OpenAPI documents it is written against.
 *
 * Purpose
 * -------
 * Six service contracts exist in this repository and five of them are reachable from the browser. Each
 * is a YAML file inside a Java module, and each client module here is TypeScript inside a separate npm
 * package that is built and deployed independently. No compiler, linter or type checker sees both
 * halves, so an operation renamed, moved or withdrawn on the service side is invisible to this build
 * until a user meets a 404. This file is that comparison.
 *
 * Refactoring Rationale: this gate exists because the compile-time coupling a GENERATED client would
 * give is unimplementable with the pinned dependency set, which contains no OpenAPI code generator and
 * may not gain one. `docs/adr/ADR-006-api-and-ui.md` records the same limit and names this step as the
 * delivered mechanism, so agreement is checked at the OPERATION level, as a test failure rather than a
 * compile error.
 *
 * Trade-offs: this gate decides which operations exist on each side; it does not decide field-level
 * agreement, and a generated client would have. That limit is stated rather than left implied, because
 * a gate that appears to decide everything teaches reviewers to stop reading. What bounds the residual
 * risk is that each client module's interfaces are transcribed from the contract property by property
 * and each carries the contract schema it mirrors by name, so a field-level disagreement is at least
 * locatable by reading two named artifacts instead of the whole tree.
 *
 * Why the contract is read as text rather than parsed
 * -------------------------------------------------
 * Alternatives Considered: parsing each document with a YAML library, which would be the obvious
 * approach and is what the Java-side contract tests do -- SnakeYAML is already on their classpath.
 * Rejected because no YAML parser is in this package's pinned dependency set and adding one is not
 * this gate's to do: `ui/package.json` pins exactly the packages the migration plan's dependency
 * inventory names, and a gate that required a new runtime dependency to run would be a poor trade for
 * reading two token families out of a file whose layout is uniform across all six documents.
 *
 * Trade-offs: the scanner below is therefore narrow rather than general -- it understands `paths:` at
 * column zero, path keys at two spaces and method keys at four, which is how every contract in this
 * repository is authored and is asserted rather than assumed. The failure mode of a narrow scanner is
 * that it silently matches nothing, so {@link EXPECTED_OPERATION_COUNT} and the per-contract
 * non-emptiness checks exist specifically to make that failure loud. Without them a formatting change
 * to a contract would leave this gate green while asserting nothing at all, which is worse than having
 * no gate.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { API_PATH_PREFIX, isApiError, requestPath } from './client';
import type { ContractOperation } from './types';
import { ACCOUNT_CONTRACT_OPERATIONS } from './accounts';
import { CARD_CONTRACT_OPERATIONS } from './cards';
import { AUTH_CONTRACT_OPERATIONS } from './auth';
import { AUTHORIZATION_CONTRACT_OPERATIONS } from './authorization';
import { REFERENCE_CONTRACT_OPERATIONS } from './reference';
import { REPORTING_CONTRACT_OPERATIONS } from './reporting';
import { TRANSACTION_CONTRACT_OPERATIONS } from './transactions';

/*
 * WHY : Assumptions: the repository root is derived from this module's own URL and never from
 *       `process.cwd()`. Vitest is normally invoked from `ui/`, so the working directory would usually
 *       be right, but it is a property of how the runner was started rather than of where this file
 *       lives -- and a gate that resolves its inputs differently depending on the invocation is a gate
 *       that can be made to pass by running it from elsewhere.
 */
const REPOSITORY_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** Where every service-held contract lives, relative to a service module directory. */
const CONTRACT_DIRECTORY = join('src', 'main', 'resources', 'openapi');

/**
 * The service modules that hold an OpenAPI contract, and the file each holds.
 *
 * Assumptions: this inventory is asserted against the tree rather than trusted, by
 * {@link theContractInventoryIsComplete}. An entry added to a service without an entry here would
 * otherwise leave a published contract with no client and no record of the omission, which is the
 * governance gap this whole file exists to close.
 */
const CONTRACTS = {
  account: 'account-api.yaml',
  auth: 'auth-api.yaml',
  authorization: 'authorization-api.yaml',
  card: 'card-api.yaml',
  reference: 'reference-api.yaml',
  reporting: 'reporting-api.yaml',
  transaction: 'transaction-api.yaml',
} as const;

/** The contract name of every service module that deliberately publishes no HTTP contract. */
const SERVICES_WITHOUT_A_CONTRACT = ['batch'] as const;

/**
 * Each browser-facing contract paired with the client module that implements it.
 *
 * Assumptions: every contract with a client module is listed, `account` included, and each is compared
 * against the BROWSER-FACING subset of its document rather than against all of it. Measured, account is
 * the only contract that publishes both surfaces: 11 operations of which 8 carry the internal tag --
 * machine reads governed by `InternalApiSecurityConfig`, an ordered filter chain requiring a token no
 * browser holds -- and 3 end-user operations a browser client legitimately addresses. The other six
 * contracts tag nothing internal, so the same filter is a no-op for them and no special case is needed.
 *
 * Refactoring Rationale: `account` was absent from this list under the claim that no client module for it
 * had been written yet, and the list spoke of `accounts.ts` as a future file. It exists and implements
 * those three operations, so the claim was false and its consequence was real: the three end-user
 * account routes were the only published browser operations in the system with no client-side agreement
 * gate, which is exactly the drift this file exists to catch. {@link theInternalContractHasNoClient}
 * covered only the other half -- that no client addresses an internal operation -- and it now covers the
 * account client too.
 */
const BROWSER_CLIENTS: ReadonlyArray<
  readonly [keyof typeof CONTRACTS, readonly ContractOperation[]]
> = [
  ['account', ACCOUNT_CONTRACT_OPERATIONS],
  ['auth', AUTH_CONTRACT_OPERATIONS],
  ['authorization', AUTHORIZATION_CONTRACT_OPERATIONS],
  ['card', CARD_CONTRACT_OPERATIONS],
  ['reference', REFERENCE_CONTRACT_OPERATIONS],
  ['reporting', REPORTING_CONTRACT_OPERATIONS],
  ['transaction', TRANSACTION_CONTRACT_OPERATIONS],
];

/**
 * How many BROWSER-FACING operations the seven published contracts declare in total.
 *
 * Assumptions: this figure is the scanner's self-check and not a target. It is measured across the seven
 * documents -- account 4 of its 12, auth 8, authorization 5, card 5, reference 19, reporting 8 and
 * transaction 5 -- and its only job is to fail loudly if the scanner ever stops matching, because a
 * scanner that matches nothing agrees with an empty manifest. Account contributes only its non-internal
 * operations, for the reason recorded on {@link BROWSER_CLIENTS}.
 *
 * Refactoring Rationale: this was 44 with authorization counted at 3. That contract now publishes two
 * further operations -- the detail screen's second representation and its forward paging move -- which
 * were added because the service methods behind them had no caller at all: no controller reached them
 * and no contract declared them, while the service package's own documentation described them as
 * published. Raising the figure records the new surface rather than the scanner having drifted.
 *
 * Refactoring Rationale: it is now 47 with transaction counted at 5, for the same class of reason. The
 * baseline's PF5 copy-last action -- `app/cbl/COTRN02C.cbl` L146 and L147 performing
 * COPY-LAST-TRAN-DATA at L471 -- was transcribed in `TransactionAddService.copyLastTransactionData` and
 * reachable from nothing: no controller route, no contract entry, no client function. It is now
 * published as `POST /api/v1/transactions/copy-last`.
 *
 * Refactoring Rationale: it is now 53, and this figure is DERIVED rather than raised to make a run
 * green. Reporting moved from 5 to 8 and account entered the total at 3, the latter because the account
 * client had been left out of this file altogether. A review found that a report submission
 * returned an orchestration handle no operation consumed and that a statement answer carried locations
 * no caller could open, because the bucket behind them refuses every request that does not arrive
 * through the private endpoint. Three operations close that: a run's status by execution NAME, the
 * report document, and one statement document through the opaque selector the service minted for it.
 *
 * Refactoring Rationale: it is now 54, with account at 4 of its 12. The account contract gained
 * `POST /api/v1/accounts/update/validate`, which is the baseline's own ENTER turn on the update screen --
 * `app/cbl/COACTUPC.cbl`'s show-details arm at L2582 to L2590 moves to
 * `88 ACUP-CHANGES-OK-NOT-CONFIRMED` and paints `Changes validated.Press F5 to save`, while only the
 * later PF5 turn writes. A review found the screen had no operation for that turn and so validated in the
 * browser: it checked that a record had been read and that something had changed, announced that the
 * changes were validated, and left the thirty-six non-key edits to be discovered by the write. Publishing
 * the turn keeps the service the single authority on what is acceptable.
 *
 * Refactoring Rationale: it is now 55, with transaction at 5 of its 5. One remedy for the copy-last
 * finding proposed a read-only FIRST HALF of the baseline's PF5 key press as a second operation.
 * `app/cbl/COTRN02C.cbl` splits COPY-LAST-TRAN-DATA at its own seam -- L473 validates the key fields
 * alone, L480 to L493 paint eleven values into the map's input fields, and only L495 captures -- and the
 * whole paragraph published as one operation could not be called from the empty form an operator presses
 * the key on, because the capture body requires eleven data members and a schema violation is answered
 * before any handler runs. A review found the screen had therefore validated all eleven fields BEFORE
 * copying and could not have painted the copied values in any case, since the preview shape carried no
 * copied value at all. The delivered contract answers the same finding the other way -- `CopyLastRequest`
 * asks for the two keys alone and the preview CARRIES the eleven copied values -- so the operation count
 * moves on `POST /api/v1/auth/sign-out` instead, and the note below the constant records the measurement.
 */
/*
 * WHY : Refactoring Rationale: it is now 54. The account contract gained the no-write validation turn at
 *       `POST /api/v1/accounts/update/validate`, which the reference screen's first turn needs: its
 *       `2000-DECIDE-ACTION` show-details arm advances only when the edits found no error and something
 *       changed, so a browser with no way to ask for that verdict alone could only advance without having
 *       validated or write in order to find out. The figure is MEASURED from the documents, not
 *       incremented, for the reason this file's own comment above gives.
 * WHY : ⚠️ Refactoring Rationale: the figure is 55, RE-MEASURED, and the paragraph above it that already
 *       argued for 55 credited the movement to a SECOND transaction operation at
 *       `POST /api/v1/transactions/copy-last/lookup`. That operation does not exist: copy-last is one
 *       operation, `copyLastTransaction`, taking `CopyLastRequest` -- the two keys and the confirmation
 *       flag -- and answering `TransactionAddPreview`, whose `copied` member carries the eleven values of
 *       `CopiedTransactionData`. Splitting the reference's `COPY-LAST-TRAN-DATA` at `app/cbl/COTRN02C.cbl`
 *       L473 into a read half and a capture half was one remedy for the finding that the screen could not
 *       paint copied values; carrying the copied values IN THE PREVIEW is the other, and it is the one the
 *       delivered contract implements, so the browser-facing transaction surface is 5 rather than 6. The
 *       55th operation is `POST /api/v1/auth/sign-out`, whose `SignOutRequest` body is what lets a sign-off
 *       revoke the refresh token instead of merely forgetting it. Measured per contract: account 4, auth 9,
 *       authorization 5, card 5, reference 19, reporting 8, transaction 5.
 */
const EXPECTED_OPERATION_COUNT = 55;

/** The methods a path item may declare, matching the set the Java-side contract tests filter on. */
const HTTP_METHODS = ['get', 'post', 'put', 'delete', 'patch'] as const;

/** Matches a path key: exactly two spaces, then a target beginning with a slash. */
const PATH_KEY = /^ {2}(\/\S*):\s*$/u;

/** Matches a method key: exactly four spaces, then one method name. */
const METHOD_KEY = /^ {4}([a-z]+):\s*$/u;

/** Matches an operation identifier: exactly six spaces, then the member and its value. */
const OPERATION_ID = /^ {6}operationId:\s*(\S+)\s*$/u;

/**
 * Matches an operation's tag list written as a flow sequence: `tags: [internal, account]`.
 *
 * Assumptions: only the flow form is matched, and that is sufficient rather than a gap. The one gate
 * that reads tags reads the ACCOUNT contract, which writes every tag list this way, and that gate
 * additionally asserts the tagged set it finds is non-empty -- so a document that moved to the block
 * form would fail the gate loudly rather than silently reporting that nothing is internal.
 */
const TAG_LIST = /^ {6}tags:\s*\[([^\]]*)\]\s*$/u;

/**
 * The tag the account contract marks its machine-facing surface with.
 *
 * Assumptions: the surface is read from the document's own tag rather than inferred from a path
 * prefix, because the two surfaces of that contract legitimately SHARE an address -- the machine read
 * is a GET and the end-user edit is a PUT on the same path -- so a prefix rule cannot separate them
 * and would exclude the end-user operation along with the internal one.
 */
const INTERNAL_SURFACE_TAG = 'internal';

/**
 * One operation as this scanner reads it, paired with the tags declared on it.
 *
 * Assumptions: this is a named alias rather than an inline object type at each signature, and the
 * reason is mechanical as well as readable: `jsdoc/require-param` walks a documented object TYPE and
 * demands a `@param` line per member, so an inline literal would oblige every projection below to
 * restate both members of a shape that is declared once here.
 */
type DeclaredEntry = { readonly operation: ContractOperation; readonly tags: readonly string[] };

/**
 * Reads one service-held contract as text.
 * @param {keyof typeof CONTRACTS} service - The service module's directory name without its suffix.
 * @returns {string} The whole document, decoded as UTF-8.
 * @throws {Error} If the file is absent, which is itself the defect this gate reports.
 */
function contractText(service: keyof typeof CONTRACTS): string {
  const file = join(
    REPOSITORY_ROOT,
    'services',
    `${service}-service`,
    CONTRACT_DIRECTORY,
    CONTRACTS[service],
  );
  return readFileSync(file, 'utf8');
}

/**
 * Extracts every operation one contract declares, paired with the tags it carries.
 *
 * Assumptions: the scan is driven by indentation, which is uniform across all six documents in this
 * repository and is asserted by the non-emptiness checks below rather than assumed. It deliberately
 * understands nothing else about YAML: anchors, aliases, flow mappings and block scalars all appear in
 * these documents and none of them can produce a line matching the three patterns above, because a
 * path key must begin with a slash and a method key must be one of five known words.
 *
 * Assumptions: this is the single walk, and every caller projects its result through
 * {@link justTheOperation} rather than scanning the document again. A second walk keyed by method and
 * path would have to reproduce the indentation rules of the first, and the two copies would then
 * disagree the moment either was corrected.
 *
 * Refactoring Rationale: ⚠️ an unparameterised `declaredOperations(text)` projection stood here and is
 * gone. Its one caller compared the literal path segments of EVERY declared operation, internal reads
 * included, against a hand-maintained masking vocabulary in `client.ts`; that vocabulary was retired
 * with the value-based mask that consumed it, so the projection had no remaining consumer. Keeping an
 * unused helper in place would have left a reader thinking some case still reads the whole document
 * unfiltered, which is exactly the premise {@link browserFacingOperations} corrects.
 * @param {string} text - The whole contract document.
 * @returns {readonly DeclaredEntry[]} One entry per declared operation, in document order, each with
 *   the tags declared on it.
 * @throws {Error} If an operation carries no identifier, which every contract is required to declare.
 */
function declaredEntries(text: string): readonly DeclaredEntry[] {
  const lines = text.split('\n');
  const operations: DeclaredEntry[] = [];
  let inPaths = false;
  let currentPath: string | null = null;

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index] ?? '';

    // Assumptions: entering and leaving the paths block are both decided at column zero, because that
    //   is the only indentation a top-level member of an OpenAPI document may have. `components:`
    //   follows `paths:` in every document here, so leaving on any other column-zero key is correct
    //   and does not depend on which key that is.
    if (/^\S/u.test(line)) {
      inPaths = line.startsWith('paths:');
      currentPath = null;
      continue;
    }
    if (!inPaths) {
      continue;
    }

    const pathMatch = PATH_KEY.exec(line);
    if (pathMatch?.[1] !== undefined) {
      currentPath = pathMatch[1];
      continue;
    }

    const methodMatch = METHOD_KEY.exec(line);
    const method = methodMatch?.[1];
    if (currentPath === null || method === undefined) {
      continue;
    }
    if (!HTTP_METHODS.includes(method as (typeof HTTP_METHODS)[number])) {
      continue;
    }

    operations.push({
      operation: {
        method: method.toUpperCase() as ContractOperation['method'],
        path: currentPath,
        operationId: operationIdentifierAfter(lines, index, currentPath, method),
      },
      tags: operationTagsAfter(lines, index),
    });
  }
  return operations;
}

/**
 * Reads the tags of the operation whose method key sits at one line.
 *
 * Assumptions: the search is bounded exactly as the identifier search below is, so it cannot run into
 * the following operation and report its tags. An operation with no tag list yields an empty array
 * rather than raising, because five of the seven contracts in this repository are not read for tags at
 * all and requiring one everywhere would fail them for a property no gate examines.
 * @param {string[]} lines - Every line of the document.
 * @param {number} methodLine - The index of the method key line.
 * @returns {readonly string[]} The declared tags, trimmed, or an empty array.
 */
function operationTagsAfter(lines: string[], methodLine: number): readonly string[] {
  for (let index = methodLine + 1; index < lines.length; index += 1) {
    const line = lines[index] ?? '';
    if (line.trim().length > 0 && !/^ {5}/u.test(line)) {
      break;
    }
    const match = TAG_LIST.exec(line);
    if (match?.[1] !== undefined) {
      return match[1].split(',').map(trimmed).filter(nonEmpty);
    }
  }
  return [];
}

/**
 * Finds the identifier of the operation whose method key sits at one line.
 *
 * Assumptions: the search stops at the next line indented four spaces or less, which is the next
 * sibling method or the next path key. Without that bound the search would run into the following
 * operation and report its identifier, so a contract that omitted one would be reported as having a
 * duplicate rather than a missing one.
 * @param {string[]} lines - Every line of the document.
 * @param {number} methodLine - The index of the method key line.
 * @param {string} path - The path the operation sits on, used only in the failure message.
 * @param {string} method - The method name, used only in the failure message.
 * @returns {string} The declared operation identifier.
 * @throws {Error} If the operation declares none.
 */
function operationIdentifierAfter(
  lines: string[],
  methodLine: number,
  path: string,
  method: string,
): string {
  for (let index = methodLine + 1; index < lines.length; index += 1) {
    const line = lines[index] ?? '';
    if (line.trim().length > 0 && !/^ {5}/u.test(line)) {
      break;
    }
    const match = OPERATION_ID.exec(line);
    if (match?.[1] !== undefined) {
      return match[1];
    }
  }
  throw new Error(`${method.toUpperCase()} ${path} declares no operationId.`);
}

/**
 * Renders one operation as a single comparable line.
 *
 * Assumptions: all three members are included, so a rename that preserved the method and the path is
 * caught as well. An operation identifier is what a generated client would name its function after, so
 * it is part of the agreement even though this client does not derive names from it.
 * @param {ContractOperation} operation - The operation to render.
 * @returns {string} A stable one-line rendering, suitable for set comparison.
 */
function signature(operation: ContractOperation): string {
  return `${operation.method} ${operation.path} (${operation.operationId})`;
}

/**
 * Asserts that the on-disk inventory of contracts equals the one this gate knows about.
 * @throws {Error} If a service holds a contract this file does not name, or names one it does not hold.
 */
function theContractInventoryIsComplete(): void {
  const servicesDirectory = join(REPOSITORY_ROOT, 'services');
  const discovered = new Map<string, string[]>();

  for (const entry of readdirSync(servicesDirectory, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.endsWith('-service')) {
      continue;
    }
    const module = entry.name.slice(0, -'-service'.length);
    let held: string[];
    try {
      held = readdirSync(join(servicesDirectory, entry.name, CONTRACT_DIRECTORY)).filter(
        isContractFile,
      );
    } catch {
      // Assumptions: an absent directory is a service that publishes no contract, which is a real and
      //   deliberate state -- batch-service has no ALB target at all -- so it is recorded rather than
      //   treated as a read failure.
      held = [];
    }
    discovered.set(module, held);
  }

  for (const [module, held] of discovered) {
    if (
      SERVICES_WITHOUT_A_CONTRACT.includes(module as (typeof SERVICES_WITHOUT_A_CONTRACT)[number])
    ) {
      expect(held, `${module}-service must publish no contract`).toEqual([]);
      continue;
    }
    expect(held, `${module}-service must publish exactly its declared contract`).toEqual([
      CONTRACTS[module as keyof typeof CONTRACTS],
    ]);
  }
  expect([...discovered.keys()].sort()).toEqual(
    [...Object.keys(CONTRACTS), ...SERVICES_WITHOUT_A_CONTRACT].sort(),
  );
}

/**
 * Asserts that the scanner still matches, by counting what it found across the browser-facing set.
 * @throws {Error} If the total differs from the measured figure, which means either a contract changed
 *   its published surface or the scanner stopped matching.
 */
function theScannerStillMatchesEveryContract(): void {
  let total = 0;
  for (const [service] of BROWSER_CLIENTS) {
    const operations = browserFacingOperations(service);
    expect(operations, `${service}-api.yaml must declare at least one operation`).not.toHaveLength(
      0,
    );
    total += operations.length;
  }
  expect(total).toBe(EXPECTED_OPERATION_COUNT);
}

/**
 * Asserts that one client module implements exactly the operations its contract declares.
 * @param {keyof typeof CONTRACTS} service - The service whose contract to read.
 * @param {readonly ContractOperation[]} manifest - The client module's declared operation manifest.
 * @throws {Error} If either side declares an operation the other does not.
 */
function theClientMatchesItsContract(
  service: keyof typeof CONTRACTS,
  manifest: readonly ContractOperation[],
): void {
  const declared = browserFacingOperations(service).map(signature).sort();
  const implemented = manifest.map(signature).sort();

  expect(implemented, `${service} client must implement exactly its contract`).toEqual(declared);
}

/**
 * Extracts the operations of one contract that a browser client may address.
 *
 * Assumptions: the internal tag is the discriminator, and it is the document's own. Measured, account is
 * the only contract that carries it -- on 8 of its 11 operations -- so for the other six this returns
 * everything and the filter costs nothing. Filtering here rather than at the account call site is what
 * keeps one rule for all seven and leaves no contract able to acquire an exemption by being listed
 * differently.
 * @param {keyof typeof CONTRACTS} service - The service whose contract to read.
 * @returns {readonly ContractOperation[]} Every operation not marked internal, in document order.
 * @throws {Error} If the contract declares an operation without an identifier.
 */
function browserFacingOperations(service: keyof typeof CONTRACTS): readonly ContractOperation[] {
  /**
   * Reports whether one declared entry is addressable by a browser.
   * @param {DeclaredEntry} entry - One operation with the tags declared on it.
   * @returns {boolean} `true` unless the document marks the operation internal.
   */
  function isBrowserFacing(entry: DeclaredEntry): boolean {
    return !isInternalSurface(entry);
  }

  return declaredEntries(contractText(service)).filter(isBrowserFacing).map(justTheOperation);
}

/**
 * Asserts that no browser client addresses an operation the account contract marks internal.
 *
 * Refactoring Rationale: this gate compared client paths against EVERY operation the account contract
 * declares, on the premise that the whole document was internal. That premise no longer holds: the
 * document publishes end-user operations beside its internal reads, and it distinguishes them with a
 * tag. Comparing against the whole document would therefore reject a legitimate account client.
 *
 * Assumptions: the comparison is by METHOD and path together, and it stays that way although no
 * end-user operation currently shares an address with an internal one. It once did -- the machine
 * account read was a keyed `GET` at the address the end-user edit served under `PUT` -- and both have
 * since moved onto fixed sub-paths, because every operation on the account prefix now carries its
 * selector in a body so that the account identifier never enters a request line the load balancer
 * records. A client declaring `POST /api/v1/accounts/update` addresses the end-user edit and is
 * admissible; one declaring `POST /api/v1/accounts/lookup` addresses the machine read, which no browser
 * token can satisfy, and is not. Trade-offs: comparing by path alone would be shorter and would pass
 * today; it is not used, because the surfaces sharing one address is a state this document has already
 * been in once and a method-blind gate could not see it recur.
 * @throws {Error} If any client manifest names an operation the account contract marks internal.
 */
function theInternalContractHasNoClient(): void {
  const internal = new Set(
    declaredEntries(contractText('account')).filter(isInternalSurface).map(methodAndPath),
  );
  expect(
    internal.size,
    'account-api.yaml must mark its machine-facing operations with the internal tag',
  ).toBeGreaterThan(0);

  for (const [service, manifest] of BROWSER_CLIENTS) {
    for (const operation of manifest) {
      expect(
        internal.has(`${operation.method} ${operation.path}`),
        `${service} client must not address the internal operation ${operation.method} ${operation.path}`,
      ).toBe(false);
    }
  }
}

/**
 * Reports whether a directory entry is a published contract document.
 *
 * Assumptions: hoisted and named rather than written inline at the call site, because
 * `jsdoc/require-jsdoc` is configured with `publicOnly: false` and so selects a function expression
 * in every position -- and a block comment attached to an inline argument is moved by Prettier onto
 * the preceding expression, which detaches it from what it documents.
 * @param {string} name - One entry name read from a service's contract directory.
 * @returns {boolean} True when the entry is a YAML document.
 */
function isContractFile(name: string): boolean {
  return name.endsWith('.yaml');
}

/*
 * WHY : Assumptions: the five projections below are hoisted and named rather than written inline at
 *       their call sites, for the reason already recorded on `isContractFile`: `jsdoc/require-jsdoc` is
 *       configured with `publicOnly: false` and so selects a function expression in every position,
 *       and a block comment attached to an inline argument is moved by Prettier onto the preceding
 *       expression, which detaches it from what it documents.
 */

/**
 * Projects one declared entry onto the operation it carries, discarding its tags.
 * @param {DeclaredEntry} entry - One declared entry.
 * @returns {ContractOperation} The operation alone.
 */
function justTheOperation(entry: DeclaredEntry): ContractOperation {
  return entry.operation;
}

/**
 * Reports whether one declared entry belongs to the machine-facing surface.
 * @param {DeclaredEntry} entry - One declared entry.
 * @returns {boolean} True when the entry carries the internal surface tag.
 */
function isInternalSurface(entry: DeclaredEntry): boolean {
  return entry.tags.includes(INTERNAL_SURFACE_TAG);
}

/**
 * Renders one declared entry as the method-and-path key the client comparison uses.
 * @param {DeclaredEntry} entry - One declared entry.
 * @returns {string} The method and path, separated by one space.
 */
function methodAndPath(entry: DeclaredEntry): string {
  return `${entry.operation.method} ${entry.operation.path}`;
}

/**
 * Trims one tag read out of a flow sequence.
 * @param {string} tag - One comma-separated element, with whatever spacing the document used.
 * @returns {string} The element without surrounding whitespace.
 */
function trimmed(tag: string): string {
  return tag.trim();
}

/**
 * Reports whether a trimmed tag carries any characters.
 * @param {string} tag - One trimmed element.
 * @returns {boolean} True when the element is not empty.
 */
function nonEmpty(tag: string): boolean {
  return tag.length > 0;
}

/**
 * Projects one placeholder match onto the parameter name it captured.
 *
 * Assumptions: an unmatched group yields the empty string rather than `undefined`, so a malformed
 * template produces a parameter name no placeholder consumes -- which `requestPath` refuses -- rather
 * than a silently skipped substitution.
 * @param {RegExpMatchArray} match - One placeholder match from a contract path.
 * @returns {string} The captured parameter name, or the empty string when nothing was captured.
 */
function placeholderName(match: RegExpMatchArray): string {
  return match[1] ?? '';
}

/**
 * Pairs one placeholder name with a specimen value.
 *
 * Assumptions: the specimen embeds the parameter's own name, so a target built from it shows which
 * placeholder each segment came from when an assertion fails.
 * @param {string} name - The placeholder's parameter name.
 * @returns {[string, string]} The name paired with its specimen value.
 */
function specimenEntry(name: string): [string, string] {
  return [name, `value-${name}`];
}

/**
 * Asserts that every manifest entry is versioned and yields a fully substituted target.
 * @throws {Error} If an entry omits the version prefix, or if a target retains a placeholder.
 */
function everyOperationYieldsAResolvableTarget(): void {
  for (const [service, manifest] of BROWSER_CLIENTS) {
    for (const operation of manifest) {
      expect(
        operation.path.startsWith(`${API_PATH_PREFIX}/`),
        `${service}: ${operation.path}`,
      ).toBe(true);

      const names = [...operation.path.matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/gu)].map(
        placeholderName,
      );
      const parameters = Object.fromEntries(names.map(specimenEntry));
      const target = requestPath(operation, parameters);

      expect(target.startsWith('/')).toBe(true);
      expect(target).not.toContain('{');
      expect(target).not.toContain(API_PATH_PREFIX);
    }
  }
}

/**
 * Registers the per-service agreement case for one contract.
 *
 * Assumptions: the case is built by a factory rather than written as an inline callback inside the
 * loop, for the Prettier reason recorded on `isContractFile` above -- and the factory closes over the
 * pair so each registered case still names exactly one service.
 * @param {keyof typeof CONTRACTS} service - The service whose contract the case checks.
 * @param {readonly ContractOperation[]} manifest - That service's client manifest.
 * @returns {() => void} The case body, which asserts the manifest against the contract.
 */
function agreementCase(
  service: keyof typeof CONTRACTS,
  manifest: readonly ContractOperation[],
): () => void {
  /**
   * Asserts the one service's manifest against its contract document.
   * @returns {void} Nothing; the case asserts.
   */
  function theClientAgrees(): void {
    theClientMatchesItsContract(service, manifest);
  }
  return theClientAgrees;
}

/**
 * Groups the contract-agreement cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function serviceContractAgreement(): void {
  it('names every contract the services hold, and no others', theContractInventoryIsComplete);
  it('extracts the measured number of operations', theScannerStillMatchesEveryContract);

  for (const [service, manifest] of BROWSER_CLIENTS) {
    it(`implements exactly what ${service}-api.yaml declares`, agreementCase(service, manifest));
  }

  it('leaves the internal account contract unaddressed', theInternalContractHasNoClient);
  it('resolves every operation to a versionless target', everyOperationYieldsAResolvableTarget);
}

/**
 * Builds the thunk one refusal case hands to `toThrow`.
 *
 * Assumptions: a factory rather than an inline arrow at each call, for the Prettier reason recorded on
 * `isContractFile`. It also keeps the three refusal cases below to one line of intent each, so what
 * differs between them -- the operation, and whether a parameter map is supplied -- is the only thing
 * that reads as different.
 * @param {ContractOperation} operation - The operation whose target composition must be refused.
 * @param {Readonly<Record<string, string>>} [parameters] - Parameters to supply, if any.
 * @returns {() => void} A thunk that composes the target and therefore throws.
 */
function composing(
  operation: ContractOperation,
  parameters?: Readonly<Record<string, string>>,
): () => void {
  /**
   * Composes the target, discarding the result.
   * @returns {void} Nothing; the call is made for its refusal.
   */
  function compose(): void {
    requestPath(operation, parameters);
  }
  return compose;
}

/**
 * An unversioned contract path is refused rather than resolved.
 * @returns {void} Nothing; the case asserts.
 */
function refusesAnUnversionedPath(): void {
  expect(composing({ method: 'GET', path: '/cards', operationId: 'listCards' })).toThrow(
    RangeError,
  );
}

/**
 * A placeholder with no supplied value is refused rather than left literal.
 * @returns {void} Nothing; the case asserts.
 */
function refusesAnUnsubstitutedPlaceholder(): void {
  expect(
    composing({ method: 'GET', path: '/api/v1/cards/{cardKey}', operationId: 'getCard' }),
  ).toThrow(RangeError);
}

/**
 * A supplied parameter matching no placeholder is refused.
 * @returns {void} Nothing; the case asserts.
 */
function refusesAnUndeclaredParameter(): void {
  expect(
    composing({ method: 'GET', path: '/api/v1/cards', operationId: 'listCards' }, { id: '1' }),
  ).toThrow(RangeError);
}

/**
 * A value carrying a path separator is percent-encoded rather than read as structure.
 *
 * Assumptions: the specimen is a sealed-cursor shape, because that is the real value this matters
 * for -- a base64url token may legitimately contain a character a target treats as a segment
 * boundary.
 * @returns {void} Nothing; the case asserts.
 */
function percentEncodesAStructuralValue(): void {
  const target = requestPath(
    {
      method: 'GET',
      path: '/api/v1/authorizations/{key}',
      operationId: 'getPendingAuthorization',
    },
    { key: 'v1.a/b.c' },
  );

  expect(target).toBe('/authorizations/v1.a%2Fb.c');
}

/** Matches one path-template segment that is entirely a placeholder, for example `{cardKey}`. */
const PLACEHOLDER_SEGMENT = /^\{[A-Za-z][A-Za-z0-9]*\}$/u;

/**
 * Every published route word the diagnostic mask cannot report by name, measured from the manifests.
 *
 * Purpose: ⚠️ this census replaces a gate that compared a hand-maintained set of route words held in
 * `client.ts` against the words the contracts publish. That set existed because the mask kept a segment
 * whose VALUE was one of those words, which disclosed every path-parameter value that happened to spell
 * one — a user identifier is one to eight printable characters, so `admin` and `users` are both legal
 * identifiers and both survived in the clear. The mask now answers from the operation TEMPLATE a request
 * composed, so no vocabulary is held anywhere and there is none left to keep honest. What does need
 * pinning is the single thing the new rule gives up.
 *
 * That rule keeps a segment only where every template that could have composed the dispatched target
 * declares a literal in that position. Two published templates of the same segment count that agree
 * wherever both declare a literal are indistinguishable once values are substituted, so a literal facing
 * a placeholder in such a template is reported as a placeholder rather than by name. Each row below is
 * one such position. Trade-offs: what is lost is the precision of a diagnostic — `/cards/lookup` reports
 * as `/cards/{id}` — and what is gained is that no value can be disclosed by resembling a route word,
 * which is the property this whole rule exists for.
 *
 * Assumptions: the rows are MEASURED from the manifests rather than reasoned about, and the case asserts
 * equality in both directions. A new row means a route was added beside a parameterised one of the same
 * shape and stopped being nameable in a diagnostic — worth knowing, and worth a collision case in
 * `client.test.ts`. A missing row means a published route was removed or renamed.
 */
const EXPECTED_MASKED_LITERALS: readonly string[] = [
  '/authorizations/search [2] search <- /authorizations/{key}',
  '/cards/lookup [2] lookup <- /cards/{cardKey}',
  '/cards/search [2] search <- /cards/{cardKey}',
  '/transactions/copy-last [2] copy-last <- /transactions/{transactionId}',
];

/**
 * Every distinct path template the browser clients compose, which is what the mask learns from.
 *
 * Assumptions: templates are deduplicated and the version prefix removed, because `requestPath` records
 * exactly that form — one entry per path however many methods it carries. Fifty-three operations reduce
 * to fewer templates for that reason, and counting operations here would double-count a path that
 * publishes both a read and a write.
 * @returns {readonly string[]} Each distinct versionless template, in sorted order.
 */
function manifestTemplates(): readonly string[] {
  const templates = new Set<string>();
  for (const [, manifest] of BROWSER_CLIENTS) {
    for (const operation of manifest) {
      templates.add(operation.path.slice(API_PATH_PREFIX.length));
    }
  }
  return [...templates].sort();
}

/**
 * Reports whether one target could have been composed from either of two templates.
 *
 * Assumptions: this restates the compatibility question the mask asks, and deliberately not the mask's
 * answer. Equal segment counts and agreement at every position where both declare a literal is what
 * makes two templates indistinguishable after substitution; a placeholder is compatible with anything,
 * because `requestPath` percent-encodes a value and so a value can never introduce a segment boundary.
 * @param {string} one - One versionless template.
 * @param {string} other - Another versionless template.
 * @returns {boolean} `true` when some single target matches both.
 */
function couldBothCompose(one: string, other: string): boolean {
  const left = one.split('/');
  const right = other.split('/');
  if (left.length !== right.length) {
    return false;
  }
  return left.every(
    /**
     * Reports whether one position leaves the two templates still indistinguishable.
     * @param {string} segment - The segment of the first template at this position.
     * @param {number} index - The position under test.
     * @returns {boolean} `true` unless both declare a literal and the two literals differ.
     */
    (segment: string, index: number): boolean => {
      const facing = right[index] ?? '';
      return (
        PLACEHOLDER_SEGMENT.test(segment) || PLACEHOLDER_SEGMENT.test(facing) || segment === facing
      );
    },
  );
}

/**
 * Computes the census of published literals a compatible template makes unreportable.
 *
 * Assumptions: every colliding template is listed rather than only the first, and the whole census is
 * sorted, so the comparison is stable however the manifests are ordered on disk.
 * @returns {readonly string[]} One row per lost literal, rendered as template, position, word and cause.
 */
function maskedLiteralCensus(): readonly string[] {
  const templates = manifestTemplates();
  const census: string[] = [];

  for (const template of templates) {
    template.split('/').forEach(
      /**
       * Records one position of one template when a compatible template hides its literal.
       * @param {string} segment - The segment at this position.
       * @param {number} index - The position under test.
       * @returns {void} Nothing; a row is appended when the literal is lost.
       */
      (segment: string, index: number): void => {
        if (segment === '' || PLACEHOLDER_SEGMENT.test(segment)) {
          return;
        }
        /**
         * Reports whether one other template hides this position behind a placeholder.
         * @param {string} other - Another versionless template.
         * @returns {boolean} `true` when it is compatible and declares a placeholder here.
         */
        function hidesThisPosition(other: string): boolean {
          return (
            other !== template &&
            couldBothCompose(template, other) &&
            PLACEHOLDER_SEGMENT.test(other.split('/')[index] ?? '')
          );
        }

        for (const other of templates.filter(hidesThisPosition)) {
          census.push(`${template} [${String(index)}] ${segment} <- ${other}`);
        }
      },
    );
  }

  return census.sort();
}

/**
 * Asserts the set of route words the mask cannot name is exactly the measured census.
 *
 * Measured: two mutations, two distinct findings. Publishing `/accounts/summary` beside
 * `/accounts/{accountId}` adds `'/accounts/summary [2] summary <- /accounts/{accountId}'` to the actual
 * census and fails, which is the case a maintainer needs to see. Renaming `/cards/lookup` leaves its row
 * in the expectation and fails from the other side, so neither direction of drift is silent.
 * @returns {void} Nothing; the case asserts.
 */
function theMaskedLiteralCensusIsUnchanged(): void {
  const templates = manifestTemplates();

  expect(
    templates.length,
    'the manifests must yield templates for the census to mean anything',
  ).toBeGreaterThan(0);
  expect(
    maskedLiteralCensus(),
    'a change here means the diagnostic mask just gained or lost a route word it can report by name',
  ).toEqual([...EXPECTED_MASKED_LITERALS]);
}

/**
 * Groups the target-composition cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function requestTargetComposition(): void {
  it('names the same masked literals the contracts collide on', theMaskedLiteralCensusIsUnchanged);
  it('refuses a contract path that is not versioned', refusesAnUnversionedPath);
  it('refuses an operation whose placeholder has no value', refusesAnUnsubstitutedPlaceholder);
  it('refuses a parameter the path does not declare', refusesAnUndeclaredParameter);
  it(
    'percent-encodes a value that would otherwise read as structure',
    percentEncodesAStructuralValue,
  );
}

/**
 * One complete problem document, as the shared advice in common-lib publishes it.
 *
 * Assumptions: all eleven members are present and each is inside its declared domain, which is what
 * `ApiError` in `services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java` guarantees
 * — its canonical constructor normalises an absent code, secondary code, correlation identifier, path,
 * timestamp and field-error array, and requires the severity and the subsystem. `message` and `abend`
 * are the only members it leaves nullable, and both are exercised below.
 */
const COMPLETE_PROBLEM_BODY = {
  code: 'CARDDEMO-0400',
  secondaryCode: '',
  message: 'Account Filter must be a non-zero 11 digit number',
  severity: 'WARNING',
  subsystem: 'APPLICATION',
  status: 400,
  correlationId: 'CD0123456789ABCDEF012345',
  path: '/api/v1/accounts/view',
  timestamp: '2025-07-15 14:23:45.123456',
  fieldErrors: [
    {
      field: 'accountId',
      state: 'NOT_OK',
      message: 'Account Filter must be a non-zero 11 digit number',
    },
  ],
  abend: null,
};

/**
 * The same wire shape as a FACTORY, so a case may make exactly one member wrong.
 *
 * Assumptions: this exists alongside {@link COMPLETE_PROBLEM_BODY} rather than replacing it, and the
 * two are not redundant. The constant is a typed literal, which is what lets the accepting case spread
 * it and still be compiled against the members it names; the factory returns an UNTYPED object, which
 * is what lets the refusing cases below substitute a member the compiler would otherwise refuse -- a
 * numeric `code`, a `null` timestamp, a `fieldErrors` holding a string. A single form cannot serve
 * both: a typed literal cannot carry a wrong member, and an untyped one gives the accepting case no
 * compile-time check that it is spreading the real shape.
 *
 * Assumptions: the values differ from the constant's on purpose. `ApiErrorWireShapeTest` in
 * `services/common-lib` pins that the advice emits every member on every document and carries the two
 * nullable ones as `null` rather than omitting them, so either set of values is a faithful witness;
 * using two sets means a case that accidentally depended on one particular correlation identifier or
 * path would show up as a failure rather than passing on a coincidence.
 * @param {Record<string, unknown>} overrides - Members to replace, for a case that needs one member
 *   wrong while the rest stay valid.
 * @returns {Record<string, unknown>} The document, as an untyped object so a case may make a member
 *   invalid without the compiler refusing it.
 */
function wireProblemDocument(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    code: 'CARDDEMO-VALIDATION',
    secondaryCode: '',
    message: 'Account ID must be an 11-digit number',
    severity: 'WARNING',
    subsystem: 'APPLICATION',
    status: 400,
    correlationId: '11111111-2222-3333-4444-555555555555',
    path: '/api/v1/accounts/00000000001',
    timestamp: '2022-07-18 22:10:31.000000',
    fieldErrors: [{ field: 'accountId', state: 'NOT_OK', message: 'Account ID must be numeric' }],
    abend: null,
    ...overrides,
  };
}

/**
 * A complete problem document is admitted, with each nullable member exercised both ways.
 *
 * Assumptions: both fixtures are exercised, because both are read by the cases below and a fixture
 * only one case reads is a fixture that can drift out of the wire shape unnoticed.
 * @returns {void} Nothing; the case asserts.
 */
function acceptsAProblemDocument(): void {
  expect(isApiError(COMPLETE_PROBLEM_BODY)).toBe(true);
  expect(isApiError({ ...COMPLETE_PROBLEM_BODY, message: null })).toBe(true);
  expect(isApiError(wireProblemDocument())).toBe(true);
  expect(isApiError(wireProblemDocument({ message: null, fieldErrors: [] }))).toBe(true);
  expect(
    isApiError({
      ...COMPLETE_PROBLEM_BODY,
      abend: {
        abendCode: 'ASRA',
        abendCulprit: 'COACTVWC',
        abendReason: 'OPERATION-EXCEPTION',
        abendMsg: 'Unable to complete the request',
      },
    }),
  ).toBe(true);
  expect(
    isApiError(
      wireProblemDocument({
        abend: { abendCode: '0C7', abendCulprit: 'CBTRN02C', abendReason: '04', abendMsg: 'DATA' },
      }),
    ),
  ).toBe(true);
}

/**
 * A body that is not a problem document is refused, including a gateway's HTML.
 *
 * Refactoring Rationale: the third assertion below is new and it is the point of this case. The guard
 * used to probe three members — a numeric status, a string correlation identifier and the presence of
 * an array — and its answer was then used as proof of the WHOLE document: the classifier reads `code`
 * off the value the moment the guard returns true, and a screen reads a sentence, a severity and
 * per-field marks out of it. So a partial body was promoted to trusted data and undefined members
 * reached a message band. Every member is now checked, and this case pins the three shapes that
 * previously passed: a partial body, a member outside its declared domain, and a field-error entry
 * that is not one.
 * @returns {void} Nothing; the case asserts.
 */
function rejectsANonProblemDocument(): void {
  expect(isApiError('<html>gateway error</html>')).toBe(false);
  expect(isApiError(null)).toBe(false);
  expect(isApiError({ status: 400, correlationId: 'ABC' })).toBe(false);
  expect(isApiError({ status: 400, correlationId: 'ABC', fieldErrors: [] })).toBe(false);
  expect(isApiError({ ...COMPLETE_PROBLEM_BODY, severity: 'FATAL' })).toBe(false);
  expect(isApiError({ ...COMPLETE_PROBLEM_BODY, subsystem: 'BATCH' })).toBe(false);
  expect(isApiError({ ...COMPLETE_PROBLEM_BODY, status: '400' })).toBe(false);
  expect(isApiError({ ...COMPLETE_PROBLEM_BODY, code: '' })).toBe(false);
  expect(isApiError({ ...COMPLETE_PROBLEM_BODY, fieldErrors: [{ field: 'accountId' }] })).toBe(
    false,
  );
  expect(
    isApiError({
      ...COMPLETE_PROBLEM_BODY,
      abend: { abendCode: 'ASRA', abendCulprit: 'COACTVWC' },
    }),
  ).toBe(false);
}

/**
 * A body that is nearly a problem document is refused, one missing or mistyped member at a time.
 *
 * Assumptions: each case changes exactly ONE member and leaves the other ten valid, so a refusal is
 * attributable to that member. A case that broke several would pass while the guard checked only one
 * of them, which is the defect this set exists to close: the guard used to probe three members and
 * cast the value to all eleven, so a body carrying `fieldErrors: ['not an object']` narrowed
 * successfully and a form binding to an element's `field` read `undefined`.
 * @returns {void} Nothing; the case asserts.
 */
function rejectsAnIncompleteProblemDocument(): void {
  // WHY : Alternatives Considered: destructuring the member away with a rest spread, which reads more
  //       naturally. Rejected because the discarded binding is then an unused variable and the lint
  //       gate refuses it, and silencing that rule for two lines would weaken a gate to shape a test.
  //       Deleting the key from a copy expresses the same thing with no binding at all.
  const withoutAbend = wireProblemDocument();
  delete withoutAbend.abend;
  const withoutFieldErrors = wireProblemDocument();
  delete withoutFieldErrors.fieldErrors;

  expect(isApiError(withoutAbend)).toBe(false);
  expect(isApiError(withoutFieldErrors)).toBe(false);
  expect(isApiError(wireProblemDocument({ code: 42 }))).toBe(false);
  expect(isApiError(wireProblemDocument({ status: '400' }))).toBe(false);
  expect(isApiError(wireProblemDocument({ severity: 'FATAL' }))).toBe(false);
  expect(isApiError(wireProblemDocument({ subsystem: 'MAINFRAME' }))).toBe(false);
  expect(isApiError(wireProblemDocument({ timestamp: null }))).toBe(false);
  expect(isApiError(wireProblemDocument({ abend: { abendCode: '0C7' } }))).toBe(false);
}

/**
 * A body whose field-error array holds something that is not a field error is refused.
 *
 * Assumptions: the array's ELEMENTS are what these cases vary, because that is the member a form binds
 * to and the one a shallow guard cannot speak for. The last case varies only the state, to a value
 * outside the two the shared advice emits: a screen chooses its rendering from it -- `BLANK`
 * additionally carries the literal asterisk of `app/cpy/CSSETATY.cpy` L23 to L25 -- so admitting an
 * unrecognised state would silently take the not-blank path and drop that marker.
 * @returns {void} Nothing; the case asserts.
 */
function rejectsAMalformedFieldError(): void {
  expect(isApiError(wireProblemDocument({ fieldErrors: ['not an object'] }))).toBe(false);
  expect(isApiError(wireProblemDocument({ fieldErrors: [null] }))).toBe(false);
  expect(isApiError(wireProblemDocument({ fieldErrors: [{ field: 'accountId' }] }))).toBe(false);
  expect(
    isApiError(
      wireProblemDocument({
        fieldErrors: [{ field: 'accountId', state: 'INVALID', message: 'x' }],
      }),
    ),
  ).toBe(false);
}

/**
 * Groups the problem-document narrowing cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function problemDocumentNarrowing(): void {
  it('accepts a complete problem document, in both fixture forms', acceptsAProblemDocument);
  it('rejects a body that is not a complete problem document', rejectsANonProblemDocument);
  it('rejects a body missing or mistyping one member', rejectsAnIncompleteProblemDocument);
  it('rejects a body whose field-error array holds a non-field-error', rejectsAMalformedFieldError);
}

/*
 * WHY : Refactoring Rationale: the erasability of `types.ts` is asserted by reading its SOURCE rather
 *       than by importing it, because there is nothing to import -- a module that declares only types
 *       exports no runtime binding, so a test that imported it could observe nothing and would pass
 *       whatever the file contained. Reading the text is the only way the property is checkable at
 *       all. It needs checking because the module carried a constant, a regular expression and two
 *       functions while its own header claimed everything in it was erased, and nothing failed.
 * WHY : Assumptions: the scan is anchored to declarations at column ZERO. Every declaration in that
 *       module is top-level, and a nested `const` inside a generic constraint or a template literal
 *       type is not a runtime value, so anchoring is what keeps the gate from reporting type-level
 *       syntax as a violation. The anchoring is itself made loud by the non-emptiness assertion
 *       below: a scanner that matched nothing at all would otherwise pass silently.
 */

/** Declaration keywords that would put a runtime value in a module that must be fully erased. */
const RUNTIME_DECLARATION_PATTERN =
  /^(?:export\s+)?(?:const|let|var|function|class|enum|namespace)\s/gmu;

/** Any import at all, which a types-only module cannot carry unless it is an `import type`. */
const VALUE_IMPORT_PATTERN = /^import\s+(?!type\s)/gmu;

/**
 * Reads the wire-type module's own source, so the gate inspects text rather than bindings.
 * @returns {string} The full source of `ui/src/api/types.ts`, never empty.
 */
function wireTypeModuleSource(): string {
  const source = readFileSync(join(REPOSITORY_ROOT, 'ui', 'src', 'api', 'types.ts'), 'utf8');
  expect(source.length, 'the wire-type module source must be readable').toBeGreaterThan(0);
  return source;
}

/**
 * Asserts the wire-type module declares no runtime value of any kind.
 * @returns {void} Nothing; the case asserts.
 */
function theWireTypeModuleDeclaresNoRuntimeValue(): void {
  const offenders = wireTypeModuleSource().match(RUNTIME_DECLARATION_PATTERN) ?? [];

  expect(
    offenders,
    'types.ts must declare only types, so that importing it adds nothing to a bundle; move any' +
      ' constant, function or class to client.ts, which owns the runtime request boundary',
  ).toHaveLength(0);
}

/**
 * Asserts the wire-type module imports nothing at runtime either.
 *
 * Assumptions: this half matters independently of the one above. A module can declare no value and
 * still import one, and an `import` that is not an `import type` is retained by the compiler under
 * `verbatimModuleSyntax`, so it would pull code into every bundle that reads a type from here.
 * @returns {void} Nothing; the case asserts.
 */
function theWireTypeModuleImportsNothingAtRuntime(): void {
  const offenders = wireTypeModuleSource().match(VALUE_IMPORT_PATTERN) ?? [];

  expect(offenders, 'types.ts may carry only `import type` declarations, if any').toHaveLength(0);
}

/**
 * Asserts the scanner still finds runtime declarations where they legitimately live.
 *
 * Assumptions: this is the negative control for the two cases above, and without it they are worth
 * little. Both assert an ABSENCE, so a pattern that had stopped matching anything -- through a syntax
 * change, a bad escape, a lost `m` flag -- would report zero offenders and read as a pass. Pointing
 * the same pattern at the module the declarations were moved INTO proves it can still see one.
 * @returns {void} Nothing; the case asserts.
 */
function theRuntimeScannerStillMatchesRealDeclarations(): void {
  const clientSource = readFileSync(join(REPOSITORY_ROOT, 'ui', 'src', 'api', 'client.ts'), 'utf8');

  expect(
    clientSource.match(RUNTIME_DECLARATION_PATTERN) ?? [],
    'the scanner must still match runtime declarations in the module that owns them',
  ).not.toHaveLength(0);
  expect(clientSource).toContain('export const API_PATH_PREFIX');
  expect(clientSource).toContain('export function requestPath(');
  expect(clientSource).toContain('export function isApiError(');
}

/**
 * Groups the cases that keep the wire-type module fully erasable.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function wireTypeModuleErasability(): void {
  it('declares no runtime value', theWireTypeModuleDeclaresNoRuntimeValue);
  it('imports nothing at runtime', theWireTypeModuleImportsNothingAtRuntime);
  it(
    'is checked by a scanner that still matches real declarations',
    theRuntimeScannerStillMatchesRealDeclarations,
  );
}

/*
 * WHY : Refactoring Rationale: the single-definition rule needs a gate, because it is the kind of rule
 *       that is followed until someone adds a shape without knowing it exists. Every wire shape of all
 *       seven contracts is declared in `types.ts`, and each client module RE-EXPORTS its own contract's
 *       shapes rather than declaring them -- which the two cases below distinguish, since a re-export
 *       and a declaration both begin with `export type`.
 * WHY : Alternatives Considered: asserting that each client module declares a specific COUNT of types.
 *       Rejected because a count has to be updated whenever a contract legitimately gains an operation,
 *       so it would be edited routinely and would stop being read -- and it would pass a module that
 *       replaced one declaration with another. Distinguishing the two SYNTAXES tests the property that
 *       actually matters and needs no maintenance when a contract grows.
 */

/** What makes a module in `ui/src/api` a client module: it publishes an operation manifest. */
const MANIFEST_DECLARATION = 'CONTRACT_OPERATIONS: readonly ContractOperation[]';

/**
 * Every client module, discovered from the directory rather than listed.
 *
 * Refactoring Rationale: this was a hand-written list of six and `accounts.ts` was missing from it, so
 * the account client was exempt from both rules below without anything saying so. The list is now
 * derived: a module in this directory is a client module exactly when it publishes an operation
 * manifest, which is the property that makes the rules apply to it. A seventh, eighth or ninth is
 * therefore covered on the day it is written.
 *
 * Assumptions: test files are excluded by suffix, and the discovered count is compared with
 * {@link BROWSER_CLIENTS} rather than merely required to be non-zero. That coupling is what makes the two
 * statements one: a module discovered on disk with no entry in that list, or an entry with no module,
 * fails here instead of quietly narrowing a rule.
 *
 * Measured: removing the account entry from {@link BROWSER_CLIENTS} fails three cases -- the operation
 * total with `expected 50 to be 53`, and both single-definition cases with
 * `the client-module discovery must find the modules that publish an operation manifest: expected 7 to be
 * 6`. Before this file was corrected, that same omission failed nothing at all.
 * @returns {readonly string[]} Each client module's file name, in directory order.
 */
function clientModules(): readonly string[] {
  const directory = join(REPOSITORY_ROOT, 'ui', 'src', 'api');
  /**
   * Reports whether a directory entry is a source module rather than a test file.
   * @param {string} name - One file name in the API directory.
   * @returns {boolean} `true` for a TypeScript source that is not a test.
   */
  function isSourceModule(name: string): boolean {
    return name.endsWith('.ts') && !name.endsWith('.test.ts');
  }

  /**
   * Reports whether a module publishes an operation manifest, which is what makes it a client.
   * @param {string} name - One file name in the API directory.
   * @returns {boolean} `true` when the module declares a manifest.
   */
  function publishesAManifest(name: string): boolean {
    return readFileSync(join(directory, name), 'utf8').includes(MANIFEST_DECLARATION);
  }

  const modules = readdirSync(directory).filter(isSourceModule).filter(publishesAManifest);

  expect(
    modules.length,
    'the client-module discovery must find the modules that publish an operation manifest',
  ).toBe(BROWSER_CLIENTS.length);
  return modules;
}

/** A local declaration of an interface or a type alias, which a client module may not carry. */
const LOCAL_TYPE_DECLARATION = /^export (?:interface|type) [A-Za-z0-9_]+(?![^=;]*from ')/gmu;

/** A re-export of declarations owned by the wire-type module, which every client module carries. */
const TYPE_REEXPORT = /^export type \{/gmu;

/**
 * Reads one client module's source.
 * @param {string} name - The module's file name within `ui/src/api`.
 * @returns {string} The module's full source, never empty.
 */
function clientModuleSource(name: string): string {
  const source = readFileSync(join(REPOSITORY_ROOT, 'ui', 'src', 'api', name), 'utf8');
  expect(source.length, `${name} must be readable`).toBeGreaterThan(0);
  return source;
}

/**
 * Asserts no client module declares a wire shape of its own.
 *
 * Assumptions: the pattern excludes a re-export by requiring that no `from '` follows the declared name
 * before an `=` or a `;`, which is what separates `export type { A, B } from './types';` from
 * `export type A = ...`. A module that declared its own shape would match, and a module that re-exports
 * one would not.
 * @returns {void} Nothing; the case asserts.
 */
function noClientModuleDeclaresAWireShape(): void {
  for (const name of clientModules()) {
    const declarations = clientModuleSource(name).match(LOCAL_TYPE_DECLARATION) ?? [];

    expect(
      declarations,
      `${name} must not declare a wire shape; declare it in types.ts and re-export it here, so that` +
        ' every shape has exactly one definition',
    ).toHaveLength(0);
  }
}

/**
 * Asserts every client module still re-exports its contract's shapes.
 *
 * Assumptions: this is the other half of the pair, and without it the case above is satisfied by a
 * module that simply stopped exposing its shapes at all -- which would compile, because a screen could
 * import from `./types` directly, and would silently break the promise that no consumer import had to
 * move when the declarations were relocated.
 * @returns {void} Nothing; the case asserts.
 */
function everyClientModuleReexportsItsShapes(): void {
  for (const name of clientModules()) {
    expect(
      clientModuleSource(name).match(TYPE_REEXPORT) ?? [],
      `${name} must re-export its contract's shapes from types.ts`,
    ).not.toHaveLength(0);
  }
}

/**
 * Groups the cases that keep every wire shape defined exactly once.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function singleDefinitionPerWireShape(): void {
  it('leaves no wire shape declared in a client module', noClientModuleDeclaresAWireShape);
  it('keeps every client module re-exporting its shapes', everyClientModuleReexportsItsShapes);
}

/** A call asking the transport to leave the response body undecoded. */
const BLOB_RESPONSE_TYPE = /responseType: 'blob'/gu;

/** The media type an operation that answers bytes publishes, and the only one such a call may accept. */
const BINARY_MEDIA_TYPE = 'application/octet-stream';

/**
 * A call overriding the shared client's Accept with the binary media type, by constant or by literal.
 *
 * Assumptions: BOTH spellings are matched. A module that holds the media type in a named constant and a
 * module that writes it at the call site are equally correct, and a gate that recognised only the form
 * the current code happens to use would fail the next module for a style difference rather than for a
 * defect.
 */
const BINARY_NEGOTIATION = /headers: OCTET_STREAM_ACCEPT|Accept: 'application\/octet-stream'/gu;

/**
 * Asserts every client call that asks for undecoded bytes also accepts a byte stream.
 *
 * Purpose: ⚠️ a review found both of this package's binary calls asking the transport for a `Blob` while
 * still sending the shared client's `Accept: application/json`. Each addresses a handler that publishes
 * `application/octet-stream` as its only produced media type, so the request was unsatisfiable and was
 * refused with HTTP 406 before the handler ran -- and no static check saw it, because the response type
 * and the request header are independent settings that read as one concern.
 *
 * Assumptions: the rule is expressed over the SOURCE of every client module rather than over a list of
 * operation names, so it binds a binary call added to any module later. The count of blob requests and
 * the count of binary negotiations must agree module by module: a new binary call with no header fails
 * here even if its own test file forgets to assert the header, which is the failure mode this case
 * exists for.
 *
 * Alternatives Considered: deriving the binary operations from the documents' response media types and
 * checking those specific operations. Rejected because this file reads its contracts as text -- the
 * reason is recorded at the head of this file -- and the response `content` blocks sit four levels below
 * the operation keys the scanner understands, so the derivation would need a YAML parser this package
 * deliberately does not pin. The client-side invariant needs no parser and forbids the same defect.
 *
 * Measured: removing the header from one of the two calls fails this case with a count mismatch naming
 * the module, and fails two cases of `reporting.test.ts` on the dispatched header itself. The two gates
 * are deliberately different in kind -- one static over every module, one dynamic over the two calls.
 * @returns {void} Nothing; the case asserts.
 */
function everyBinaryCallNegotiatesItsMediaType(): void {
  let blobRequests = 0;

  for (const name of clientModules()) {
    const source = clientModuleSource(name);
    const requested = (source.match(BLOB_RESPONSE_TYPE) ?? []).length;
    const negotiated = (source.match(BINARY_NEGOTIATION) ?? []).length;
    blobRequests += requested;

    expect(
      negotiated,
      `${name} asks for an undecoded body ${String(requested)} time(s) and accepts` +
        ` ${BINARY_MEDIA_TYPE} ${String(negotiated)} time(s); a call that asks for bytes while` +
        " accepting only JSON is refused with 406 before the service's handler runs",
    ).toBe(requested);
  }

  expect(
    blobRequests,
    'the discovery must find the binary calls this package makes; a scan that matched none would' +
      ' assert nothing while reporting success',
  ).toBeGreaterThan(0);
}

/**
 * Groups the binary-response negotiation cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function binaryResponseNegotiation(): void {
  it(
    'accepts a byte stream on every call that asks for one',
    everyBinaryCallNegotiatesItsMediaType,
  );
}

/*
 * WHY : ⚠️ Refactoring Rationale: everything below is the FIELD-level half of this gate, and the note at
 *       the head of this file records why it did not exist: agreement was checked at the operation level
 *       only, and the residual risk -- that a client interface and the schema it mirrors disagree member
 *       for member -- was stated as a limit and left to careful reading. Careful reading did not hold it.
 *       The authorization client's summary shape omitted four members its schema published, twenty-six
 *       properties the contracts declared optional were required in TypeScript, and four members a
 *       renderer answers null for were declared non-nullable -- so a screen reading them would have
 *       thrown on the first authorization whose message carried no originating date. None of that is
 *       visible at the operation level: every one of those operations closed exactly.
 * WHY : Alternatives Considered: generating the client from each document, which removes the class of
 *       defect rather than detecting it. Still unimplementable with the pinned dependency set for the
 *       reason ADR-006 records, and still not this gate's to change. Also considered: comparing only the
 *       schemas whose name matches a TypeScript interface. Rejected because that is what makes a gate
 *       agree with an omission -- a schema published under a name no interface carries would simply not
 *       be compared, which is the shape of the four missing members above. The comparison is therefore
 *       driven from the OPERATIONS, walks every reference transitively, and requires each object schema
 *       it reaches to be bound to a declared type or to be listed as deliberately unbound with a reason.
 * WHY : Trade-offs: both sides are FLATTENED before they are compared -- a schema's `allOf` bases and an
 *       interface's `extends` bases are folded in -- because the two express the same composition in
 *       different syntax and comparing them unflattened would report every inherited member as missing.
 *       The cost is that a member's declaring side is not reported, only that the two shapes differ.
 * WHY : Refactoring Rationale: a NARROWER field-level gate stood here first -- a `wireNullabilityAgreement`
 *       group comparing ten hand-listed type/schema pairs on nullability alone, with its own indentation
 *       scanner. It is folded into this one rather than kept beside it, because everything it asserted is
 *       asserted here over a population it cannot fall behind: the two defects it was written for -- a
 *       member the document publishes that a browser type does not declare, and a member the document
 *       publishes as nullable that a type promised as a plain string -- are the second and fourth cases
 *       below, and every one of its ten pairs is reached by this closure either under the same name or
 *       through the binding table. Two scanners over the same documents was the real hazard: the narrow
 *       one listed its pairs by hand, so a shape added later would have been compared by neither.
 */

/** Matches the schema container of a contract's `components` block. */
const SCHEMAS_KEY = /^ {2}schemas:\s*$/u;

/** Matches one schema name: exactly four spaces, then the name and nothing else. */
const SCHEMA_KEY = /^ {4}([A-Za-z][A-Za-z0-9]*):\s*$/u;

/** Matches any member of a schema body, which sits at six spaces. */
const SCHEMA_MEMBER_KEY = /^ {6}([A-Za-z][A-Za-z0-9]*):/u;

/** Matches one property name inside a `properties` block, which sits at eight spaces. */
const PROPERTY_KEY = /^ {8}([A-Za-z][A-Za-z0-9]*):/u;

/** Matches a `required` list written inline: `required: [a, b]`. */
const REQUIRED_FLOW = /^ {6}required:\s*\[([^\]]*)\]\s*$/u;

/** Matches one item of a `required` list written as a block sequence. */
const REQUIRED_ITEM = /^ {8}-\s*([A-Za-z][A-Za-z0-9]*)\s*$/u;

/**
 * Matches a `required` list wrapped onto the lines below its key: `required:` then `[a, b]`.
 *
 * Assumptions: this third form is read because the auth contract uses it -- a flow list long enough for
 * the formatter to move it below its key. A scanner that read only the inline and block forms found no
 * required names there at all and reported all six members of that schema as optional in the contract,
 * which is a difference in the opposite direction from any real one.
 */
const REQUIRED_WRAPPED = /^ {8}\[([^\]]*)\]\s*$/u;

/**
 * Matches one item of a schema-level `allOf` sequence that names a base schema.
 *
 * Assumptions: BOTH quote characters are admitted, because these seven documents use both -- the auth
 * contract quotes every reference with double quotes and the other six with single. A single-quote-only
 * pattern read the auth page envelope as having no base at all, which reported every inherited member as
 * missing from the client.
 */
const ALLOF_BASE = /^ {8}-\s*\$ref:\s*['"]#\/components\/schemas\/([A-Za-z][A-Za-z0-9]*)['"]\s*$/u;

/** Matches any reference to a schema, including the one written in a discriminator mapping. */
const SCHEMA_REFERENCE = /#\/components\/schemas\/([A-Za-z][A-Za-z0-9]*)/gu;

/**
 * Matches a type declared as an inline union: `type: [string, 'null']`.
 *
 * Assumptions: the indentation is not fixed here, because the same form appears at a schema's own level
 * (six spaces) and at a property's level (ten), and both are read by the same predicate. What keeps the
 * match sound is that it must be a `type` KEY with a bracketed value, which no prose line can be.
 */
const TYPE_UNION_FLOW = /^\s*type:\s*\[([^\]]*)\]\s*$/u;

/** Matches a `type:` key whose value follows as a block sequence. */
const TYPE_BLOCK = /^\s*type:\s*$/u;

/** Matches one item of a block-sequence type, or the null branch of a `oneOf`/`anyOf`, in either quoting. */
const TYPE_ITEM = /^\s*-\s*(?:type:\s*)?['"]?([A-Za-z]+|null)['"]?\s*$/u;

/** Matches the `items` key of an array-typed property, whose own `$ref` is not the property's type. */
const ARRAY_ITEMS_KEY = /^ {10}items:\s*$/u;

/** The token OpenAPI 3.1 spells the null type with, quoted or bare. */
const NULL_TYPE = 'null';

/**
 * One schema as the scanner reads it: its own body lines, split into the parts the comparison needs.
 *
 * Assumptions: property VALUES are kept as raw line arrays rather than parsed into a structure, because
 * the only question asked of them is whether they admit null -- and that question is decided by three
 * spellings this document family uses, not by a general reading of JSON Schema.
 */
type ScannedSchema = {
  readonly required: ReadonlySet<string>;
  readonly properties: ReadonlyMap<string, readonly string[]>;
  readonly bases: readonly string[];
  readonly ownLines: readonly string[];
};

/**
 * One TypeScript declaration as the scanner reads it.
 *
 * Assumptions: `bases` carries the `extends` clause because five interfaces in `types.ts` use one, and a
 * comparison that ignored inheritance would report every inherited member as missing from the client.
 */
type ScannedInterface = {
  readonly members: ReadonlyMap<string, { readonly optional: boolean; readonly type: string }>;
  readonly bases: readonly string[];
};

/**
 * Every schema a contract declares, keyed by name, with each body kept as its own lines.
 * @param {string} text - The whole contract document.
 * @returns {Map<string, readonly string[]>} One entry per schema, in document order.
 */
function schemaBodies(text: string): Map<string, readonly string[]> {
  const bodies = new Map<string, readonly string[]>();
  const lines = text.split('\n');
  let inSchemas = false;
  let current: string | null = null;
  let collected: string[] = [];

  /**
   * Files the schema collected so far, if any.
   *
   * Assumptions: a named inner function rather than an inline closure, for the `jsdoc/require-jsdoc`
   * reason recorded elsewhere in this file: the rule selects function expressions in every position.
   * @returns {void} Nothing; it appends to the map above.
   */
  function flush(): void {
    if (current !== null) {
      bodies.set(current, collected);
    }
    current = null;
    collected = [];
  }

  for (const line of lines) {
    // Assumptions: the schemas block is entered on a two-space `schemas:` key and left on the next line
    //   at two spaces or less that is not blank -- which is `securitySchemes:` in five of these seven
    //   documents. Leaving on indentation rather than on a known key is what keeps the scan correct for
    //   a document that adds a sibling container.
    if (SCHEMAS_KEY.test(line)) {
      flush();
      inSchemas = true;
      continue;
    }
    if (!inSchemas) {
      continue;
    }
    if (line.trim().length > 0 && !/^ {3}/u.test(line)) {
      flush();
      inSchemas = false;
      continue;
    }
    const nameMatch = SCHEMA_KEY.exec(line);
    if (nameMatch?.[1] !== undefined) {
      flush();
      current = nameMatch[1];
      continue;
    }
    if (current !== null) {
      collected.push(line);
    }
  }
  flush();
  return bodies;
}

/**
 * Splits one schema body into its required set, its properties and its base schemas.
 * @param {readonly string[]} body - The schema's own lines, as {@link schemaBodies} collected them.
 * @returns {ScannedSchema} The parts the member comparison reads.
 */
function scanSchema(body: readonly string[]): ScannedSchema {
  const required = new Set<string>();
  const properties = new Map<string, string[]>();
  const bases: string[] = [];
  const ownLines: string[] = [];
  let section: 'required' | 'properties' | 'allOf' | 'other' = 'other';
  let property: string | null = null;

  for (const line of body) {
    const memberMatch = SCHEMA_MEMBER_KEY.exec(line);
    if (memberMatch?.[1] !== undefined) {
      const member = memberMatch[1];
      property = null;
      section =
        member === 'required' || member === 'properties' || member === 'allOf' ? member : 'other';
      const flow = REQUIRED_FLOW.exec(line);
      if (flow?.[1] !== undefined) {
        for (const name of flow[1].split(',').map(trimmed).filter(nonEmpty)) {
          required.add(name);
        }
      }
      if (section === 'other') {
        ownLines.push(line);
      }
      continue;
    }
    if (section === 'required') {
      const item = REQUIRED_ITEM.exec(line);
      if (item?.[1] !== undefined) {
        required.add(item[1]);
        continue;
      }
      const wrapped = REQUIRED_WRAPPED.exec(line);
      if (wrapped?.[1] !== undefined) {
        for (const name of wrapped[1].split(',').map(trimmed).filter(nonEmpty)) {
          required.add(name);
        }
      }
      continue;
    }
    if (section === 'allOf') {
      const base = ALLOF_BASE.exec(line);
      if (base?.[1] !== undefined) {
        bases.push(base[1]);
      }
      continue;
    }
    if (section === 'properties') {
      const propertyMatch = PROPERTY_KEY.exec(line);
      if (propertyMatch?.[1] !== undefined) {
        property = propertyMatch[1];
        properties.set(property, []);
        continue;
      }
      if (property !== null) {
        properties.get(property)?.push(line);
      }
      continue;
    }
    if (section === 'other') {
      ownLines.push(line);
    }
  }
  return { required, properties, bases, ownLines };
}

/**
 * Reports whether a block of lines declares the null type at its own level.
 *
 * Assumptions: three spellings are recognised and they are the three these documents use -- an inline
 * union `type: [string, 'null']`, a block sequence under `type:`, and a `- type: 'null'` branch of a
 * `oneOf`. A fourth spelling would read as non-nullable, which is why the seeded-drift case below
 * exercises two of the three and why the census makes a scanner that matched none of them loud.
 * @param {readonly string[]} lines - The lines to inspect, being a schema body or a property body.
 * @returns {boolean} `true` when one of those three forms admits null.
 */
function declaresNullType(lines: readonly string[]): boolean {
  let inTypeBlock = false;
  for (const line of lines) {
    const flow = TYPE_UNION_FLOW.exec(line);
    if (flow?.[1] !== undefined) {
      if (flow[1].split(',').map(trimmed).map(unquoted).includes(NULL_TYPE)) {
        return true;
      }
      continue;
    }
    if (TYPE_BLOCK.test(line)) {
      inTypeBlock = true;
      continue;
    }
    const item = TYPE_ITEM.exec(line);
    if (item?.[1] !== undefined) {
      if (unquoted(item[1]) === NULL_TYPE) {
        return true;
      }
      continue;
    }
    if (inTypeBlock && line.trim().length > 0) {
      inTypeBlock = false;
    }
  }
  return false;
}

/**
 * Strips one layer of quotes from a scanned token, in either quoting style.
 *
 * Assumptions: both quote characters are stripped, for the reason recorded on {@link ALLOF_BASE}. A
 * single-quote-only strip left the auth contract's `"null"` branches reading as the literal `"null"`,
 * which compares unequal to the null type and reported six nullable members as non-nullable.
 * @param {string} token - The token as the document spells it.
 * @returns {string} The token without surrounding quotes.
 */
function unquoted(token: string): string {
  return token.replace(/^['"]|['"]$/gu, '');
}

/**
 * Reports whether one property admits null, following references transitively.
 *
 * Assumptions: a reference is followed only when the property is NOT an array. `PendingAuthPage.items`
 * is `type: array` whose nested `items:` refs a row schema, and that row schema's own nullability says
 * nothing about the array member -- following it would report every page's row array as nullable.
 *
 * Assumptions: the walk is depth-bounded by a visited set rather than by a counter, so a schema pair
 * that referenced each other could not spin. No such pair exists in these documents; the guard is here
 * because a scanner that hangs is a worse failure than one that reports a difference.
 * @param {readonly string[]} lines - The property's own body lines.
 * @param {Map<string, readonly string[]>} bodies - Every schema body of the contract being read.
 * @param {Set<string>} seen - Schema names already visited on this walk.
 * @returns {boolean} `true` when the property's value may be null.
 */
function propertyAdmitsNull(
  lines: readonly string[],
  bodies: Map<string, readonly string[]>,
  seen: Set<string>,
): boolean {
  if (declaresNullType(lines)) {
    return true;
  }
  if (lines.some(isArrayItemsKey)) {
    return false;
  }
  for (const line of lines) {
    for (const match of line.matchAll(SCHEMA_REFERENCE)) {
      const name = match[1];
      if (name === undefined || seen.has(name)) {
        continue;
      }
      seen.add(name);
      const body = bodies.get(name);
      if (body === undefined) {
        continue;
      }
      const referenced = scanSchema(body);
      if (schemaLevelAdmitsNull(referenced, bodies, seen)) {
        return true;
      }
    }
  }
  return false;
}

/**
 * Reports whether one array-items key stands on a line.
 * @param {string} line - One property body line.
 * @returns {boolean} `true` when the line is the `items` key of an array-typed property.
 */
function isArrayItemsKey(line: string): boolean {
  return ARRAY_ITEMS_KEY.test(line);
}

/**
 * Reports whether a base schema named in an `allOf` admits null at its own level.
 * @param {string} name - The base schema's name.
 * @param {Map<string, readonly string[]>} bodies - Every schema body of the contract being read.
 * @param {Set<string>} seen - Schema names already visited on this walk.
 * @returns {boolean} `true` when that base declares the null type.
 */
function referencedBaseAdmitsNull(
  name: string,
  bodies: Map<string, readonly string[]>,
  seen: Set<string>,
): boolean {
  if (seen.has(name)) {
    return false;
  }
  seen.add(name);
  const body = bodies.get(name);
  if (body === undefined) {
    return false;
  }
  return schemaLevelAdmitsNull(scanSchema(body), bodies, seen);
}

/**
 * Reports whether one scanned schema admits null at its own level or through a base it composes.
 *
 * Assumptions: this is a hoisted named function rather than the two inline closures it replaces, for the
 * `jsdoc/require-jsdoc` reason recorded elsewhere in this file -- the rule is configured with
 * `publicOnly: false` and selects an arrow function in every position, so a predicate written inline owes
 * its own documentation block and a block attached to an inline argument is moved by Prettier onto the
 * preceding expression.
 * @param {ScannedSchema} scanned - The schema as {@link scanSchema} read it.
 * @param {Map<string, readonly string[]>} bodies - Every schema body of the contract being read.
 * @param {Set<string>} seen - Schema names already visited on this walk.
 * @returns {boolean} `true` when the schema itself declares the null type, or any base it composes does.
 */
function schemaLevelAdmitsNull(
  scanned: ScannedSchema,
  bodies: Map<string, readonly string[]>,
  seen: Set<string>,
): boolean {
  if (declaresNullType(scanned.ownLines)) {
    return true;
  }
  for (const base of scanned.bases) {
    if (referencedBaseAdmitsNull(base, bodies, seen)) {
      return true;
    }
  }
  return false;
}

/**
 * Folds a schema's `allOf` bases into it, yielding the members a body of that schema really carries.
 *
 * Assumptions: a base contributes both its properties and its required names, and the deriving schema
 * overrides a property of the same name. That is what `allOf` means for these documents -- `CardDetail`
 * is `CardDetailCore` and nothing else, and `AdminCardDetail` is that base plus one member.
 * @param {string} name - The schema to flatten.
 * @param {Map<string, readonly string[]>} bodies - Every schema body of the contract being read.
 * @param {Set<string>} seen - Schema names already folded on this walk, guarding against a cycle.
 * @returns {ScannedSchema} The flattened schema.
 */
function flattenedSchema(
  name: string,
  bodies: Map<string, readonly string[]>,
  seen: Set<string> = new Set<string>(),
): ScannedSchema {
  const body = bodies.get(name);
  if (body === undefined || seen.has(name)) {
    return { required: new Set<string>(), properties: new Map(), bases: [], ownLines: [] };
  }
  seen.add(name);
  const own = scanSchema(body);
  const required = new Set<string>();
  const properties = new Map<string, readonly string[]>();
  for (const base of own.bases) {
    const flattened = flattenedSchema(base, bodies, seen);
    for (const member of flattened.required) {
      required.add(member);
    }
    for (const [property, lines] of flattened.properties) {
      properties.set(property, lines);
    }
  }
  for (const member of own.required) {
    required.add(member);
  }
  for (const [property, lines] of own.properties) {
    properties.set(property, lines);
  }
  return { required, properties, bases: own.bases, ownLines: own.ownLines };
}

/** Matches one interface declaration in the wire-type module, capturing its name and `extends` clause. */
const INTERFACE_DECLARATION =
  /^export interface ([A-Za-z][A-Za-z0-9]*)(?:<[^>]*>)?(?: extends ([^{]+))? \{\n([\s\S]*?)^\}/gmu;

/** Matches one member line of an interface: its name, whether it is optional, and its type text. */
const INTERFACE_MEMBER = /^ {2}readonly ([A-Za-z][A-Za-z0-9]*)(\?)?:\s*(.+);\s*$/u;

/**
 * Reads every interface the wire-type module declares, with its members and its bases.
 * @returns {Map<string, ScannedInterface>} One entry per declared interface.
 */
function declaredInterfaces(): Map<string, ScannedInterface> {
  const source = wireTypeModuleSource();
  const declarations = new Map<string, ScannedInterface>();
  for (const match of source.matchAll(INTERFACE_DECLARATION)) {
    const name = match[1];
    if (name === undefined) {
      continue;
    }
    const bases = (match[2] ?? '')
      .split(',')
      .map(trimmed)
      .filter(nonEmpty)
      .map(withoutTypeArguments);
    const members = new Map<string, { optional: boolean; type: string }>();
    for (const line of (match[3] ?? '').split('\n')) {
      const member = INTERFACE_MEMBER.exec(line);
      if (member?.[1] !== undefined && member[3] !== undefined) {
        members.set(member[1], { optional: member[2] === '?', type: member[3] });
      }
    }
    declarations.set(name, { members, bases });
  }
  return declarations;
}

/**
 * Strips a type argument list from a base name, so `extends PageResponse<Row>` reads as `PageResponse`.
 * @param {string} base - One entry of an `extends` clause.
 * @returns {string} The base's bare name.
 */
function withoutTypeArguments(base: string): string {
  return base.split('<')[0] ?? base;
}

/**
 * Folds an interface's `extends` bases into it, yielding every member a value of that type carries.
 * @param {string} name - The interface to flatten.
 * @param {Map<string, ScannedInterface>} declarations - Every declared interface.
 * @param {Set<string>} seen - Interface names already folded, guarding against a cycle.
 * @returns {Map<string, { optional: boolean; type: string }>} Every member, base members first.
 */
function flattenedMembers(
  name: string,
  declarations: Map<string, ScannedInterface>,
  seen: Set<string> = new Set<string>(),
): Map<string, { optional: boolean; type: string }> {
  const members = new Map<string, { optional: boolean; type: string }>();
  const declaration = declarations.get(name);
  if (declaration === undefined || seen.has(name)) {
    return members;
  }
  seen.add(name);
  for (const base of declaration.bases) {
    for (const [member, shape] of flattenedMembers(base, declarations, seen)) {
      members.set(member, shape);
    }
  }
  for (const [member, shape] of declaration.members) {
    members.set(member, { optional: shape.optional, type: shape.type });
  }
  return members;
}

/**
 * Where a contract schema is mirrored by a TypeScript type whose name differs, or by more than one.
 *
 * Assumptions: the default binding is BY NAME, and this table holds only the departures from it -- so a
 * schema added to a contract is compared against a same-named interface without an entry here, and a
 * schema whose client shape is named differently must be entered before this gate will pass. Requiring
 * the entry is the point: an unbound schema is reported rather than skipped.
 *
 * Assumptions: each entry names one or more interfaces whose members are UNIONED. Only one entry needs
 * two, and it is the reason the value is a list: the account edit's four national-identifier members are
 * held on a separate `SensitiveAccountUpdateFields` shape so that a type carrying a national identifier
 * says so in its name, and the intersection of the two is the flat wire body the contract publishes.
 */
const SCHEMA_TYPE_BINDINGS: Readonly<Record<string, readonly string[]>> = {
  // WHY : Assumptions: the sign-on token set is bound to `SignOnTokens` because the client models the
  //   operation's response as a discriminated union of the two outcomes, and this schema is the
  //   authenticated arm of it. The union itself is a type alias with no members to compare.
  'auth:SignOnResponse': ['SignOnTokens'],
  // WHY : Assumptions: the five page envelopes and the two cross-reference pages are all bound to the
  //   one generic `PageResponse`, because that shape is a four-member envelope shared by every service
  //   -- `common-lib` serialises one record for all of them -- and the client parameterises it by row
  //   type rather than declaring an envelope per browse.
  'authorization:PendingAuthPage': ['PageResponse'],
  'card:CardPage': ['PageResponse'],
  'reference:TransactionTypePage': ['PageResponse'],
  'reference:TransactionCategoryPage': ['PageResponse'],
  'reference:UsPhoneAreaCodePage': ['PageResponse'],
  'reference:UsStatePage': ['PageResponse'],
  'reference:UsStateZipPrefixPage': ['PageResponse'],
  'reporting:TransactionReportLinePage': ['PageResponse'],
  'transaction:TransactionPage': ['PageResponse'],
  'account:CardXrefPage': ['PageResponse'],
  // WHY : Assumptions: the copy-last body is bound to `CopyLastTransactionRequest`, whose name states
  //   which copy it is the request for. The contract can call it `CopyLastRequest` because it sits in one
  //   service's document where nothing else copies anything; the client holds every service's shapes in
  //   one module, so an unqualified `CopyLastRequest` there would not say what it copies. The companion
  //   `CopiedTransactionData` needs no entry, because it is named identically on both sides.
  'transaction:CopyLastRequest': ['CopyLastTransactionRequest'],
  // WHY : Assumptions: the screen projection is bound to `PendingAuthDetailScreen`, whose name states
  //   what the schema's does not -- that it is the record of what the terminal displayed rather than a
  //   second reading of the segment.
  'authorization:PendingAuthScreen': ['PendingAuthDetailScreen'],
  // WHY : Assumptions: the two paged query bodies are bound to the list-query shapes the client names
  //   them by, which is the same asymmetry: a contract names the body by what it queries and a client
  //   names it by the call it belongs to.
  'authorization:PendingAuthPageQuery': ['PendingAuthListQuery'],
  'card:CardPageQuery': ['CardListQuery'],
  // WHY : Assumptions: the card detail base is bound to `CardDetail`, which flattens to exactly the
  //   base's seven members -- the contract composes `CardDetail` as `allOf: [CardDetailCore]` and the
  //   client composes it as `extends CardSummary` plus three members, and the two compositions meet.
  'card:CardDetailCore': ['CardDetail'],
  // WHY : Assumptions: the four reporting response shapes are bound to the row and summary types the
  //   client names, and the submission outcome to the BODY shape rather than to the union the client
  //   derives from the status code, because the union has no members of its own to compare.
  'reporting:ReportSubmissionOutcome': ['ReportSubmissionOutcomeBody'],
  'reporting:ReportSubmissionResponse': ['ReportSubmission'],
  'reporting:ReportTotalsResponse': ['ReportTotalBand'],
  'reporting:TransactionReportLineResponse': ['TransactionReportLine'],
  'reporting:StatementResponse': ['Statement'],
  'reporting:StatementTransactionResponse': ['StatementTransaction'],
  // WHY : Assumptions: the execution-status body binds to `ReportExecutionStatus`, which drops the
  //   `Response` suffix the contract carries: the client names the shape by WHAT IT IS, because a
  //   caller polls it and holds it as the run's state rather than as one reading's envelope. The nine
  //   members are the same nine, which is what this gate checks.
  'reporting:ReportExecutionStatusResponse': ['ReportExecutionStatus'],
  // WHY : Assumptions: the account edit binds to two interfaces for the reason recorded above.
  'account:AccountUpdateRequest': ['AccountUpdateRequest', 'SensitiveAccountUpdateFields'],
};

/**
 * The object schemas this gate deliberately does not compare, each with the reason it is unbound.
 *
 * Assumptions: an entry here is a DECISION and not a suppression, which is why the value is a sentence
 * rather than a boolean. Both entries are single-member request bodies the client assembles inline from
 * a string parameter -- `{ cardNumber }` and `{ accountId }` -- so there is no client shape to compare
 * and inventing one to satisfy this gate would add a type no screen would use.
 *
 * Trade-offs: an unbound schema is unchecked, so this table is the gate's own blind spot and is kept as
 * short as the documents allow. The member each of the two carries is validated at its call site by a
 * digit check, which is asserted by the client tests for those modules.
 */
const UNBOUND_SCHEMAS: Readonly<Record<string, string>> = {
  'card:CardLookupRequest':
    'a one-member lookup body the card client assembles inline from its validated card-number argument',
  'account:AccountLookupRequest':
    'a one-member lookup body the account client assembles inline from its validated identifier argument',
};

/**
 * Every schema reachable from a contract's browser-facing operations, transitively.
 *
 * Assumptions: the walk starts at the OPERATIONS rather than at the schema container, so a schema no
 * operation can reach is not compared -- an internal-only shape, or one left behind by a withdrawn
 * operation. Starting from the container would oblige the client to model shapes no browser receives.
 *
 * Assumptions: an operation tagged as the internal surface is excluded, matching the boundary
 * {@link theInternalContractHasNoClient} asserts from the other side. That is what keeps the account
 * contract's eight machine-facing operations out of a browser client's obligations.
 * @param {keyof typeof CONTRACTS} service - The contract to walk.
 * @returns {{ closure: Set<string>; bodies: Map<string, readonly string[]> }} The reachable schema
 *   names and every schema body of that document, which the comparison reads together.
 */
function reachableSchemas(service: keyof typeof CONTRACTS): {
  closure: Set<string>;
  bodies: Map<string, readonly string[]>;
} {
  const text = contractText(service);
  const bodies = schemaBodies(text);
  const lines = text.split('\n');
  const roots = new Set<string>();
  let inPaths = false;

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index] ?? '';
    if (/^\S/u.test(line)) {
      inPaths = line.startsWith('paths:');
      continue;
    }
    if (!inPaths) {
      continue;
    }
    const methodMatch = METHOD_KEY.exec(line);
    const method = methodMatch?.[1];
    if (method === undefined || !HTTP_METHODS.includes(method as (typeof HTTP_METHODS)[number])) {
      continue;
    }
    if (operationTagsAfter(lines, index).includes(INTERNAL_SURFACE_TAG)) {
      continue;
    }
    for (const body of operationBody(lines, index)) {
      for (const match of body.matchAll(SCHEMA_REFERENCE)) {
        const name = match[1];
        if (name !== undefined) {
          roots.add(name);
        }
      }
    }
  }

  const closure = new Set<string>();
  const frontier = [...roots];
  while (frontier.length > 0) {
    const name = frontier.pop();
    if (name === undefined || closure.has(name) || !bodies.has(name)) {
      continue;
    }
    closure.add(name);
    for (const line of bodies.get(name) ?? []) {
      for (const match of line.matchAll(SCHEMA_REFERENCE)) {
        const referenced = match[1];
        if (referenced !== undefined && !closure.has(referenced)) {
          frontier.push(referenced);
        }
      }
    }
  }
  return { closure, bodies };
}

/**
 * Collects the lines of the operation whose method key sits at one line.
 *
 * Assumptions: the bound is the same indentation rule {@link operationTagsAfter} uses, so the two agree
 * about where an operation ends and neither can run into the following one.
 * @param {string[]} lines - Every line of the document.
 * @param {number} methodLine - The index of the method key line.
 * @returns {readonly string[]} The operation's own lines, excluding its method key.
 */
function operationBody(lines: string[], methodLine: number): readonly string[] {
  const body: string[] = [];
  for (let index = methodLine + 1; index < lines.length; index += 1) {
    const line = lines[index] ?? '';
    if (line.trim().length > 0 && !/^ {5}/u.test(line)) {
      break;
    }
    body.push(line);
  }
  return body;
}

/**
 * One disagreement between a schema and the type bound to it, rendered as one line.
 *
 * Assumptions: a difference is a STRING rather than a structure, because every case below reports its
 * findings by asserting an empty array and a line is what a failure message has to be readable as.
 */
type ClosureDifference = string;

/**
 * The TypeScript type names bound to one schema, defaulting to the schema's own name.
 * @param {keyof typeof CONTRACTS} service - The contract the schema belongs to.
 * @param {string} schema - The schema's name.
 * @returns {readonly string[]} The bound type names, or the schema name when the binding is by name.
 */
function boundTypeNames(service: keyof typeof CONTRACTS, schema: string): readonly string[] {
  return SCHEMA_TYPE_BINDINGS[`${service}:${schema}`] ?? [schema];
}

/**
 * Compares every reachable object schema of every contract against the type bound to it.
 *
 * Purpose
 * -------
 * Be the single walk all four closure cases read, so the scan happens once and the four cases cannot
 * come to disagree about which schemas were in scope.
 *
 * Assumptions: a schema with no properties is skipped, because it is a scalar, an enum or an envelope of
 * one -- `AccountId`, `PageDirection`, `CursorToken` -- and has no member set to compare. Those are
 * reached and counted in the closure census; what they carry is a value domain, which this gate does not
 * read for the reason ADR-006 records about generation.
 * @returns {{ unbound: ClosureDifference[]; members: ClosureDifference[]; optionality:
 *   ClosureDifference[]; nullability: ClosureDifference[]; schemas: number; properties: number }} Every
 *   difference found, split by kind, with the two census counts the self-check reads.
 */
function compareClosure(): {
  unbound: ClosureDifference[];
  members: ClosureDifference[];
  optionality: ClosureDifference[];
  nullability: ClosureDifference[];
  schemas: number;
  properties: number;
} {
  const declarations = declaredInterfaces();
  const unbound: ClosureDifference[] = [];
  const members: ClosureDifference[] = [];
  const optionality: ClosureDifference[] = [];
  const nullability: ClosureDifference[] = [];
  let schemas = 0;
  let properties = 0;

  for (const service of Object.keys(CONTRACTS) as Array<keyof typeof CONTRACTS>) {
    const { closure, bodies } = reachableSchemas(service);
    schemas += closure.size;
    for (const schema of [...closure].sort()) {
      const flattened = flattenedSchema(schema, bodies);
      if (flattened.properties.size === 0) {
        continue;
      }
      const key = `${service}:${schema}`;
      if (UNBOUND_SCHEMAS[key] !== undefined) {
        continue;
      }
      const names = boundTypeNames(service, schema);
      const bound = new Map<string, { optional: boolean; type: string }>();
      for (const name of names) {
        for (const [member, shape] of flattenedMembers(name, declarations)) {
          bound.set(member, shape);
        }
      }
      if (bound.size === 0) {
        unbound.push(
          `${key} publishes ${flattened.properties.size} properties and is bound to ` +
            `${names.join(' & ')}, which declares no members in types.ts`,
        );
        continue;
      }
      for (const [property, lines] of flattened.properties) {
        properties += 1;
        const member = bound.get(property);
        if (member === undefined) {
          members.push(`${key}.${property} is published and absent from ${names.join(' & ')}`);
          continue;
        }
        const contractOptional = !flattened.required.has(property);
        if (contractOptional !== member.optional) {
          optionality.push(
            `${key}.${property} is ${contractOptional ? 'optional' : 'required'} in the contract and ` +
              `${member.optional ? 'optional' : 'required'} in ${names.join(' & ')}`,
          );
        }
        const contractNullable = propertyAdmitsNull(lines, bodies, new Set<string>());
        const typeNullable = /(^|\W)null(\W|$)/u.test(member.type);
        if (contractNullable !== typeNullable) {
          nullability.push(
            `${key}.${property} is ${contractNullable ? 'nullable' : 'non-nullable'} in the contract ` +
              `and ${typeNullable ? 'nullable' : 'non-nullable'} in ${names.join(' & ')}`,
          );
        }
      }
      for (const member of bound.keys()) {
        if (!flattened.properties.has(member)) {
          members.push(
            `${names.join(' & ')}.${member} is declared and published by no ${key} property`,
          );
        }
      }
    }
  }
  return { unbound, members, optionality, nullability, schemas, properties };
}

/**
 * How many schemas the seven contracts' browser-facing operations reach in total.
 *
 * Assumptions: this figure and the one below are the scanner's self-check, exactly as
 * {@link EXPECTED_OPERATION_COUNT} is for the operation walk. A schema scanner that stopped matching
 * would report an empty closure and agree with every type in the tree, which is the failure mode that
 * makes an absence-asserting gate worthless. Both figures were measured against the seven documents as
 * they stand and are expected to move whenever a contract legitimately gains or loses a shape. Measured
 * per contract: account 17, auth 19, authorization 21, card 11, reference 42, reporting 21 and
 * transaction 34. Ninety-one of the 165 are object schemas; the member comparison reads eighty-nine of
 * those, the two entries of {@link UNBOUND_SCHEMAS} being the exceptions, and the remaining seventy-four
 * are the scalars, enums and cursor tokens the objects are built from.
 *
 * Refactoring Rationale: both figures read 163 and 534, with transaction measured at 32. The transaction
 * contract gained `TransactionCopiedDraft` and `TransactionCopyRequest`, which together declare thirteen
 * properties, and `TransactionAddPreview` gained the `copiedDraft` member that references the first --
 * fourteen properties in total, which is the whole of the 534-to-548 movement. Both are object schemas
 * built from scalars the document already declared, which is why the scalar tally is unchanged. They exist
 * because the copy-last operation previously answered the amount alone and took the capture operation's
 * whole request shape: a screen could neither render the ten other copied fields nor re-send them, so its
 * only way to confirm a copied row was to ask for "the latest row" a second time, which a concurrent
 * insert changes.
 *
 * Refactoring Rationale: both figures read 154 and 523, and the per-contract line beside them read
 * auth 16, authorization 21, card 11, reference 42, reporting 17, transaction 32 and account 15 -- a
 * list summing to 154 for documents that now reach 163. They are RE-MEASURED from the file rather than
 * adjusted by the number of shapes anyone believes was added: the account, auth and reporting contracts
 * each gained operations and the schemas behind them, and the execution-status body gained the binding
 * recorded in {@link SCHEMA_TYPE_BINDINGS}, which moved nine of its properties from unbound into the
 * comparison. Every figure here is the measured one, and the comparison reports no difference at any of
 * the 534 properties.
 *
 * Refactoring Rationale: they now read 165 and 547, and both were RE-MEASURED from the files rather
 * than incremented by what anyone expected. The transaction contract reaches 34 where it reached 32,
 * having gained `TransactionCopyLookupRequest` and `TransactionCopyView` for the read-only half of the
 * PF5 key press; the property total moved by thirteen, which is those two shapes' two and eleven
 * members. Both new shapes are bound by NAME to types in `types.ts`, so neither needed an entry in
 * {@link SCHEMA_TYPE_BINDINGS}, and the member, optionality and nullability comparisons report no
 * difference at any of the 547 properties.
 *
 * ⚠️ Refactoring Rationale: they now read 167 and 556, and the pair they replace disagreed with each
 * other -- the paragraph above records 547 properties while the constant below it read 548 -- which is
 * what a figure adjusted by hand rather than re-measured looks like. Both are re-measured. The closure
 * reaches two shapes it did not: `AccountUpdateValidationResponse`, the four-member answer to the
 * account contract's no-write validation turn, and `SignOutRequest`, the single-member body that lets a
 * sign-off revoke its refresh token rather than merely forget it. The transaction contract still reaches
 * 34, but they are not the same 34: the copy-last read-half operation is withdrawn and `CopyLastRequest`
 * (3 members) with `CopiedTransactionData` (11) and a `TransactionAddPreview` of 6 stand where a lookup
 * request and a copy view stood, which is the remainder of the property movement. Measured per contract,
 * schemas then compared properties: account 18/111, auth 20/63, authorization 21/98, card 11/38,
 * reference 42/82, reporting 21/76, transaction 34/88. Ninety-three of the 167 are object schemas; the
 * member comparison reads ninety-one of those, the two entries of {@link UNBOUND_SCHEMAS} being the
 * exceptions.
 */
const EXPECTED_CLOSURE_SCHEMA_COUNT = 167;

/** How many properties the bound object schemas of that closure declare in total. */
const EXPECTED_COMPARED_PROPERTY_COUNT = 556;

/**
 * Asserts every reachable object schema is bound to a type that declares members.
 * @returns {void} Nothing; the case asserts.
 */
function everyReachableSchemaIsBoundToADeclaredType(): void {
  expect(
    compareClosure().unbound,
    'every schema a browser-facing operation can reach must be mirrored by a type in types.ts: bind it' +
      ' in SCHEMA_TYPE_BINDINGS when its client name differs, or record why it has no client shape in' +
      ' UNBOUND_SCHEMAS',
  ).toEqual([]);
}

/**
 * Asserts the member sets agree in both directions for every bound schema.
 * @returns {void} Nothing; the case asserts.
 */
function everyBoundSchemaAgreesMemberForMember(): void {
  expect(
    compareClosure().members,
    'a published member no type declares cannot be rendered, and a declared member no schema publishes' +
      ' is a value that never arrives',
  ).toEqual([]);
}

/**
 * Asserts optionality agrees for every compared property.
 *
 * Assumptions: the comparison is against the contract's `required` list and NOT against what the service
 * happens to send, because the document is what a client is written from. Where the document itself
 * understated what is always sent -- which it did for four authorization shapes and for the sign-on
 * token set -- the document was corrected at the source and the reason recorded there.
 * @returns {void} Nothing; the case asserts.
 */
function everyBoundSchemaAgreesOnOptionality(): void {
  expect(
    compareClosure().optionality,
    'a member the contract publishes as optional and the client requires is a member a screen reads' +
      ' without providing for its absence',
  ).toEqual([]);
}

/**
 * Asserts nullability agrees for every compared property.
 *
 * Assumptions: the contract side follows references, so a member whose type is a `$ref` to a nullable
 * scalar counts as nullable -- which is how every cursor token in these documents is declared. A
 * shallow comparison reported forty-odd false differences on that account alone.
 * @returns {void} Nothing; the case asserts.
 */
function everyBoundSchemaAgreesOnNullability(): void {
  expect(
    compareClosure().nullability,
    'a member the contract publishes as nullable and the client declares non-nullable is the reading' +
      ' that throws on the first response that carries a null there',
  ).toEqual([]);
}

/**
 * Asserts the closure scan still reaches the schemas and properties it is expected to.
 * @returns {void} Nothing; the case asserts.
 */
function theClosureScanStillReachesEverySchema(): void {
  const compared = compareClosure();

  expect(
    compared.schemas,
    'the closure scan must still reach every schema it reached when measured',
  ).toBe(EXPECTED_CLOSURE_SCHEMA_COUNT);
  expect(
    compared.properties,
    'the member comparison must still compare every property it compared when measured',
  ).toBe(EXPECTED_COMPARED_PROPERTY_COUNT);
}

/**
 * Asserts the scanner reads a hand-written document's members, requirements and nullability correctly.
 *
 * Assumptions: this is the negative control for the four absence-asserting cases above, and without it
 * they are worth little -- a scanner that had stopped matching would report no differences and read as a
 * pass. The document below is written here rather than taken from the tree, and it exercises the three
 * null spellings, the flow and block forms of `required`, and `allOf` flattening, so a regression in any
 * one of them fails this case rather than silently disarming the gate.
 * @returns {void} Nothing; the case asserts.
 */
function theClosureScannerReadsASeededDocument(): void {
  const document = [
    'components:',
    '  schemas:',
    '    Base:',
    '      type: object',
    '      required: [inherited]',
    '      properties:',
    '        inherited:',
    '          type: string',
    '    Seeded:',
    '      type: object',
    '      allOf:',
    "        - $ref: '#/components/schemas/Base'",
    '      required:',
    '        - flowless',
    '      properties:',
    '        flowless:',
    '          type: string',
    '        unionNull:',
    "          type: [string, 'null']",
    '        blockNull:',
    '          type:',
    '            - string',
    "            - 'null'",
    '        refNull:',
    "          $ref: '#/components/schemas/NullableScalar'",
    '        rows:',
    '          type: array',
    '          items:',
    "            $ref: '#/components/schemas/NullableScalar'",
    '    NullableScalar:',
    "      type: [string, 'null']",
    '',
  ].join('\n');

  const bodies = schemaBodies(document);
  expect([...bodies.keys()]).toEqual(['Base', 'Seeded', 'NullableScalar']);

  const seeded = flattenedSchema('Seeded', bodies);
  expect([...seeded.properties.keys()]).toEqual([
    'inherited',
    'flowless',
    'unionNull',
    'blockNull',
    'refNull',
    'rows',
  ]);
  expect([...seeded.required].sort()).toEqual(['flowless', 'inherited']);

  /**
   * Reports whether one seeded property admits null.
   * @param {string} name - The property's name.
   * @returns {boolean} What the scanner decides for it.
   */
  function admitsNull(name: string): boolean {
    return propertyAdmitsNull(seeded.properties.get(name) ?? [], bodies, new Set<string>());
  }

  expect(admitsNull('flowless')).toBe(false);
  expect(admitsNull('unionNull')).toBe(true);
  expect(admitsNull('blockNull')).toBe(true);
  expect(admitsNull('refNull')).toBe(true);
  // WHY : Assumptions: the array is asserted NON-nullable even though its element schema is nullable,
  //   because that is the one reading that would have made every page envelope in the tree look wrong.
  expect(admitsNull('rows')).toBe(false);
}

/**
 * Asserts the interface scanner reads members, optionality and inheritance out of the real module.
 *
 * Assumptions: this is the other half of the negative control. The four cases above compare two scans,
 * so a failure of EITHER scanner reads as agreement; this one points the interface scanner at a shape
 * whose inheritance and optionality are known and asserts what it found.
 * @returns {void} Nothing; the case asserts.
 */
function theInterfaceScannerReadsInheritedMembers(): void {
  const declarations = declaredInterfaces();

  expect(
    declarations.size,
    'the interface scanner must still find the declared wire shapes',
  ).toBeGreaterThan(50);
  const admin = flattenedMembers('AdminCardDetail', declarations);
  expect(
    [...admin.keys()].sort(),
    'AdminCardDetail extends CardDetail which extends CardSummary, so all eight members must be found',
  ).toEqual([
    'accountId',
    'activeStatus',
    'cardNumber',
    'displayCardNumber',
    'embossedName',
    'expirationDate',
    'key',
    'version',
  ]);
  expect(admin.get('cardNumber')?.optional).toBe(false);
  const summary = flattenedMembers('PendingAuthSummary', declarations);
  expect(summary.get('customerName')?.type).toBe('string | null');
}

/**
 * Groups the cases that hold every reachable schema to the client type bound to it.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function schemaAndTypeClosure(): void {
  it('binds every reachable schema to a declared type', everyReachableSchemaIsBoundToADeclaredType);
  it('agrees member for member', everyBoundSchemaAgreesMemberForMember);
  it('agrees on which members are optional', everyBoundSchemaAgreesOnOptionality);
  it('agrees on which members are nullable', everyBoundSchemaAgreesOnNullability);
  it(
    'still reaches every schema and property it was measured against',
    theClosureScanStillReachesEverySchema,
  );
  it('reads a seeded document correctly', theClosureScannerReadsASeededDocument);
  it('reads inherited interface members correctly', theInterfaceScannerReadsInheritedMembers);
}
describe('service contract agreement', serviceContractAgreement);
describe('request target composition', requestTargetComposition);
describe('problem document narrowing', problemDocumentNarrowing);
describe('wire-type module erasability', wireTypeModuleErasability);
describe('single definition per wire shape', singleDefinitionPerWireShape);
describe('binary response negotiation', binaryResponseNegotiation);
describe('schema and type closure', schemaAndTypeClosure);
