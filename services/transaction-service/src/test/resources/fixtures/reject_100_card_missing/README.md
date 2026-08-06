# reject_100_card_missing -- a card number that resolves to no cross-reference row

Scenario README for `fixtures/reject_100_card_missing/`. The reasoning that is
identical across all ten scenarios lives in the folder index at
[`../README.md`](../README.md) and is cited here by section number rather than copied;
the byte-encoding contract lives in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) and is
cited by section number and never restated.

---

## 1. Intent

This scenario exists to prove the first reject in the validation order: a card number
that cannot be resolved to a cross-reference row is rejected with reason code 100
before any account is looked at.

It is the earliest exit in the chain, and that ordering is part of what it asserts.
The account lookup, the credit-limit comparison and the expiration comparison all sit
after it and are never reached, so this record's amount and date are left at the
template's passing values on purpose.

---

## 2. The rule it exercises

The cross-reference lookup in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), inside
`1500-A-LOOKUP-XREF` whose header is line 380:

```text
MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
READ XREF-FILE INTO CARD-XREF-RECORD
   INVALID KEY
     MOVE 100 TO WS-VALIDATION-FAIL-REASON
     MOVE 'INVALID CARD NUMBER FOUND'
       TO WS-VALIDATION-FAIL-REASON-DESC
   NOT INVALID KEY
     CONTINUE
END-READ
```

That is lines 382-391: the key is moved on 382, the read is issued on 383, and the
`INVALID KEY` arm on 384 sets reason code 100 on 385 and the message on 386.

The reason then being non-zero, line 211 takes its `ELSE` on line 213, the reject count
is incremented on line 214 and `2500-WRITE-REJECT-REC` runs on line 215. Posting on
line 212 never runs, and `1500-B-LOOKUP-ACCT` on line 393 -- which would have supplied
the account values the two later gates need -- is not reached either.

The card number in this fixture is `9999999999999999`, and its unresolvability is a
**measurement** rather than an assumption:
[`app/data/ASCII/cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt)
holds 50 rows and none of them begins with that value.

---

## 3. Expected outcome

- One reject, carrying reason code **100** and the message `INVALID CARD NUMBER FOUND`
  byte for byte as declared on line 386. The message is 25 characters and carries no
  trailing punctuation. It is the only reject message in this program that names the
  card rather than the account.
- No posted transaction, no account-balance change and no category-balance change:
  lines 440-442 sit inside `2000-POST-TRANSACTION`, which is not reached.
- The reject count becomes 1, so line 229 is true and the return code is moved to **4**
  on line 230 -- a soft warn, because a correctly written business reject is the program
  working as specified.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Folder section 3.1 is the authority for this folder shipping no cross-reference image
even though the rule under test reads one: the cross-reference belongs to the `account`
schema, owned by another module, and `ledger` is the only schema this module owns.
Folder section 2.3 is the authority for shipping no reject image: a reject is an output
of posting rather than an input to it, so the expected code and message are stated in
prose above.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt)
   with one field reshaped, per item 3. `transact.txt` and `tcatbal.txt` are
   byte-identical to their `happy_path` counterparts, whose provenance is recorded in
   [`../happy_path/README.md`](../happy_path/README.md) section 4.2.
2. **No real person and no real account.** Every account number, card number, name and
   address byte here comes from the published CardDemo demonstration seed, except the
   card number of item 3, which is a placeholder that matches nothing in the seed. None
   of them identifies a real person or a real account, and none is a credential.
3. **Which bytes were reshaped away from the seed value.** One field in
   `dailytran.txt`: the card number at positions 263-278, moved from the seed's
   `4859452612877065` to `9999999999999999`. Fifteen of the sixteen bytes change --
   position 266 already held a `9` in the seed, measured -- and nothing else in the
   record moves. Note that this scenario's `transact.txt` still carries the seed's card
   number, unchanged; see section 5.

---

## 5. Three decisions about this record

Assumptions: the replacement stays exactly sixteen bytes and digits-only. The column
in the target is `CHAR(16)`, and the reject this scenario asserts must arise from the
lookup finding nothing -- not from a width failure, not from a character-domain
failure, and not from a numeric conversion. An all-nines value of the correct width and
domain fails at exactly one place, which is the place being tested.

Alternatives Considered: a shorter or blank card number would also fail to resolve, and
both were rejected for that reason. Either would fail earlier and for a different
cause, so the assertion would pass while covering a different code path, and the
scenario would silently stop testing line 383.

Refactoring Rationale: `9999999999999999` was chosen over a value that merely happens
to be absent today. Folder section 7 requires the identity bytes to be attestably
synthetic, and an all-nines value is visibly a placeholder -- no reader will mistake it
for a seeded value, and it cannot be confused with any of the 50 rows of the seed
cross-reference. A plausible-looking sixteen-digit number, by contrast, would need its
absence re-measured every time the seed changed and would read like real data in a log.

Trade-offs: `transact.txt` in this scenario still carries the seed's card number rather
than the unresolvable one. That is deliberate. Those two files are independent table
images and not a before-and-after pair of one transaction, per folder section 3.1, and
`ledger.transactions` is a posted master whose rows are supposed to reference resolvable
cards. Propagating the placeholder into it would assert that an unresolvable card had
already posted -- a state the baseline cannot produce, since line 383 rejects such a
record before line 442 ever writes one.
