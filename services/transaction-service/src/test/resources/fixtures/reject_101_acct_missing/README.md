# reject_101_acct_missing -- the resolved account does not exist

Scenario README for `fixtures/reject_101_acct_missing/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than copied;
the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This scenario exists to prove the second reject in the validation order: a card number
that **does** resolve to a cross-reference row, whose account identifier then resolves
to nothing, is rejected with reason code 101.

Its distinguishing property is therefore a two-part precondition -- the card must
resolve and the account must not. Neither part is carried by a byte this fixture moves:
the resolvable half is carried by the card the template already holds, and the
absent-account half is a state the consuming test establishes. Section 5 says so plainly
rather than implying otherwise.

---

## 2. The rule it exercises

The account lookup in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), inside
`1500-B-LOOKUP-ACCT` whose header is line 393:

```text
MOVE XREF-ACCT-ID TO FD-ACCT-ID
READ ACCOUNT-FILE INTO ACCOUNT-RECORD
   INVALID KEY
     MOVE 101 TO WS-VALIDATION-FAIL-REASON
     MOVE 'ACCOUNT RECORD NOT FOUND'
       TO WS-VALIDATION-FAIL-REASON-DESC
   NOT INVALID KEY
     ...
```

That is lines 394-400: the key is moved on 394 from `XREF-ACCT-ID` -- the value the
**previous** paragraph read -- the read is issued on 395, and the `INVALID KEY` arm on
396 sets reason code 101 on 397 and the message on 398.

Reaching line 395 at all requires the cross-reference read on line 383 to have
**succeeded**, because `XREF-ACCT-ID` is only populated by that read. That is the whole
reason this scenario cannot reuse the unresolvable card number of
`reject_100_card_missing`: with an unresolvable card the flow exits at line 385 and this
paragraph never runs.

The card number here is `4859452612877065` -- the template's own value, which is row 21
of [`app/data/ASCII/cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt) and
resolves to customer `000000007` and account `00000000007`, measured. This
`dailytran.txt` is therefore byte-identical to
[`../happy_path/dailytran.txt`](../happy_path/dailytran.txt), which is deliberate: it is
the construction the reference tree uses for this same scenario, whose
`reject_101_acct_missing/dailytran.txt` also carries the happy card and separates itself
by shipping an `acctdata.txt` holding a different account. Folder section 1.3 carries the
reasoning, and its note there records why substituting a different seed card here is
specifically wrong rather than merely unnecessary.

The reason then being non-zero, line 211 takes its `ELSE` on line 213, the reject count
is incremented on line 214 and `2500-WRITE-REJECT-REC` runs on line 215. The two later
gates on lines 407 and 414 sit inside the `NOT INVALID KEY` arm of this same read, so
neither is evaluated.

---

## 3. Expected outcome

- One reject, carrying reason code **101** and the message `ACCOUNT RECORD NOT FOUND`
  byte for byte as declared on line 398. The message is 24 characters and carries no
  trailing punctuation.
- No posted transaction, no account-balance change and no category-balance change: lines
  440-442 sit inside `2000-POST-TRANSACTION`, which is not reached.
- The reject count becomes 1, so line 229 is true and the return code is moved to **4**
  on line 230.

An assertion for this scenario must check the reason **code** and not only the
description. Folder section 1.2 records why: the message on line 398 is byte-identical
to the one reason 109 writes on line 557, so a description-only assertion cannot tell
this scenario from `reject_109_rewrite_invalid_key` and will pass against the wrong one.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Folder section 3.1 is the authority for this folder shipping neither an account image
nor a cross-reference image even though the rule under test reads both: they belong to
the `account` schema, owned by another module.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is byte-identical to record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured rather than asserted, with no field reshaped. `transact.txt` and
   `tcatbal.txt` are byte-identical to their `happy_path` counterparts -- also measured
   -- whose provenance is recorded in
   [`../happy_path/README.md`](../happy_path/README.md) section 4.2.
2. **No real person and no real account.** Every account number, card number, name and
   address byte here comes from the published CardDemo demonstration seed. They are
   demonstration values, not credentials, and they identify no real person and no real
   account. Every byte of all three files is a published seed byte, so the attestation
   here rests on the same measurement as `happy_path`'s and adds no value of its own.
3. **Which bytes were reshaped away from the seed value.** In `dailytran.txt`, none --
   the record is the seed row unchanged, and `cmp` against `../happy_path/dailytran.txt`
   reports the two as identical, measured. In `transact.txt`, the processing timestamp
   at positions 305-330 only, and in `tcatbal.txt` the account identifier and balance,
   exactly as `happy_path` item 3 records for those two files. This scenario's
   discriminator is not a byte of any of the three, so nothing here is attested as
   reshaped for its sake; section 5 records where the discriminator lives instead.

---

## 5. What this fixture does not establish, and why

Assumptions: **account `00000000007` exists in the published seed.** It is row 7 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
measured, and it is the account the seed cross-reference resolves this record's card to.
The precondition this scenario is named for is therefore **not** established by these
three files and **not** established by the published seed either. It is established by
the account state the consuming test seeds -- a subset that omits that account -- and
this folder ships no account image at all, per folder section 3.1.

The consequence is concrete and is the single most useful thing in this document: a test
that seeds all fifty published accounts verbatim will see this record **post**, and the
101 assertion will fail. The failure will look like a fixture defect and will not be
one. Whoever writes that test must seed the account tier without account `00000000007`,
which is exactly what the reference tree does -- its `reject_101_acct_missing/acctdata.txt`
holds one row, and that row is account `00000000020`, measured. The cross-reference tier,
by contrast, must include the row for this card, or the record expresses reason 100
instead; folder section 1.3 records why that failure mode is the one to guard against
here.

Alternatives Considered: minting a card number that resolves to an account identifier
absent from the seed -- `00000000099`, say -- would make the precondition self-contained.
It was rejected because it requires authoring a cross-reference row to do the resolving,
and a cross-reference row is exactly the other-context record folder section 3.1 forbids
placing here. Using the seed card that the seed cross-reference already maps keeps the
resolvable half of the precondition measurable, which is the half this folder can own.

Refactoring Rationale: this fixture previously carried `0927987108636232`, chosen for a
second property -- it is the leading-zero card that folder section 4.2 item 1 names as
forbidden to normalise, so one field was carrying both the resolvable-card half of the
101 precondition and the `CHAR(16)` round-trip evidence. It was withdrawn in favour of
the template card because the two properties are not compatible here. `0927987108636232`
resolves only if the consuming test seeds row 4 of the cross-reference specifically; the
minimal cross-reference tier that every resolvable-card scenario in the reference corpus
ships maps `4859452612877065` alone, and under that tier the card resolves to nothing.
Because `CBTRN02C` checks reason 100 at lines 385-387 before reason 101 at lines
397-399, this scenario would then have asserted its predecessor while still being named
for 101 -- the one outcome a fixture must never make plausible.

Trade-offs: the withdrawal costs this scenario any visible marker of its own, so its
identity now rests on its folder name and on the account state a test supplies, and it
costs the corpus the leading-zero round-trip that the old card carried incidentally. Both
are accepted. A fixture whose bytes contradict its name is worse than one with no marker,
and the round-trip property is better served by a fixture authored and named for it, as
the closing paragraph of folder section 4.2 records.
