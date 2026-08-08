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
 * Refactoring Rationale: it exists because `docs/adr/ADR-006-api-and-ui.md` recorded a decision that
 * the typed client layer is "built against those documents, so a field that is renamed, retyped or
 * removed on one side is a compile-time failure on the other". No generator and no gate existed, so
 * the stated mechanism was not merely unimplemented -- it was unimplementable with the pinned
 * dependency set, which contains no OpenAPI code generator and may not gain one. What is delivered
 * instead is asserted here and described in that ADR: agreement is checked at the OPERATION level, as a
 * test failure rather than a compile error.
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

import { API_PATH_PREFIX, isApiError, requestPath } from './types';
import type { ContractOperation } from './types';
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
 * Assumptions: `account` is ABSENT because no client module for it has been written yet, and NOT
 * because none may be. That is a change of reason rather than of the list: the account contract used to
 * publish an internal read surface alone -- three operations governed by `InternalApiSecurityConfig`,
 * an ordered filter chain requiring a machine token that no browser holds -- and it now publishes three
 * end-user operations beside them, which a browser client legitimately may address.
 *
 * Trade-offs: the three end-user account operations therefore have no client-side gate at present, so a
 * drift between them and a future `accounts.ts` would not be caught here until that module is added to
 * this list. What IS still enforced is the boundary that matters for correctness:
 * {@link theInternalContractHasNoClient} asserts that no client addresses an operation the contract
 * marks internal, keyed by method and path, so a client cannot acquire the machine read by sharing an
 * address with the end-user edit.
 */
const BROWSER_CLIENTS: ReadonlyArray<
  readonly [keyof typeof CONTRACTS, readonly ContractOperation[]]
> = [
  ['auth', AUTH_CONTRACT_OPERATIONS],
  ['authorization', AUTHORIZATION_CONTRACT_OPERATIONS],
  ['card', CARD_CONTRACT_OPERATIONS],
  ['reference', REFERENCE_CONTRACT_OPERATIONS],
  ['reporting', REPORTING_CONTRACT_OPERATIONS],
  ['transaction', TRANSACTION_CONTRACT_OPERATIONS],
];

/**
 * How many operations the five browser-facing contracts declare in total.
 *
 * Assumptions: this figure is the scanner's self-check and not a target. It was measured across the
 * five documents while this gate was authored -- auth 8, authorization 3, card 5, reference 19,
 * reporting 5 and transaction 4 -- and its only job is to fail loudly if the scanner ever stops
 * matching, because a scanner that matches nothing agrees with an empty manifest.
 */
const EXPECTED_OPERATION_COUNT = 44;

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
 * Extracts every operation one contract declares.
 *
 * Assumptions: the scan is driven by indentation, which is uniform across all six documents in this
 * repository and is asserted by the non-emptiness checks below rather than assumed. It deliberately
 * understands nothing else about YAML: anchors, aliases, flow mappings and block scalars all appear in
 * these documents and none of them can produce a line matching the three patterns above, because a
 * path key must begin with a slash and a method key must be one of five known words.
 * @param {string} text - The whole contract document.
 * @returns {ContractOperation[]} One entry per declared operation, in document order.
 * @throws {Error} If an operation carries no identifier, which every contract is required to declare.
 */
function declaredOperations(text: string): ContractOperation[] {
  return declaredEntries(text).map(justTheOperation);
}

/**
 * Extracts every operation one contract declares, paired with the tags it carries.
 *
 * Assumptions: this is the single walk and {@link declaredOperations} projects its result, rather than
 * the two scanning the document separately. A second walk keyed by method and path would have to
 * reproduce the indentation rules of the first, and the two copies would then disagree the moment
 * either was corrected.
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
    const operations = declaredOperations(contractText(service));
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
  const declared = declaredOperations(contractText(service)).map(signature).sort();
  const implemented = manifest.map(signature).sort();

  expect(implemented, `${service} client must implement exactly its contract`).toEqual(declared);
}

/**
 * Asserts that no browser client addresses an operation the account contract marks internal.
 *
 * Refactoring Rationale: this gate compared client paths against EVERY operation the account contract
 * declares, on the premise that the whole document was internal. That premise no longer holds: the
 * document now publishes three end-user operations beside its three internal reads, and it distinguishes
 * them with a tag. Comparing against the whole document would therefore reject a legitimate account
 * client, and comparing by path alone could not have separated the surfaces in any case -- the machine
 * read and the end-user edit share one address and differ only in method.
 *
 * Assumptions: the comparison is by METHOD and path together for that reason. A client declaring
 * `PUT /api/v1/accounts/{accountId}` addresses the end-user edit and is admissible; one declaring
 * `GET` on the same address addresses the machine read, which no browser token can satisfy, and is
 * not.
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

/**
 * Groups the target-composition cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function requestTargetComposition(): void {
  it('refuses a contract path that is not versioned', refusesAnUnversionedPath);
  it('refuses an operation whose placeholder has no value', refusesAnUnsubstitutedPlaceholder);
  it('refuses a parameter the path does not declare', refusesAnUndeclaredParameter);
  it(
    'percent-encodes a value that would otherwise read as structure',
    percentEncodesAStructuralValue,
  );
}

/**
 * A body carrying the three members a caller acts on is admitted.
 * @returns {void} Nothing; the case asserts.
 */
function acceptsAProblemDocument(): void {
  expect(isApiError({ status: 400, correlationId: 'ABC', fieldErrors: [] })).toBe(true);
}

/**
 * A body that is not a problem document is refused, including a gateway's HTML.
 * @returns {void} Nothing; the case asserts.
 */
function rejectsANonProblemDocument(): void {
  expect(isApiError('<html>gateway error</html>')).toBe(false);
  expect(isApiError(null)).toBe(false);
  expect(isApiError({ status: 400, correlationId: 'ABC' })).toBe(false);
}

/**
 * Groups the problem-document narrowing cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function problemDocumentNarrowing(): void {
  it('accepts a body carrying the three members a caller acts on', acceptsAProblemDocument);
  it('rejects a body that is not a problem document', rejectsANonProblemDocument);
}

describe('service contract agreement', serviceContractAgreement);
describe('request target composition', requestTargetComposition);
describe('problem document narrowing', problemDocumentNarrowing);
