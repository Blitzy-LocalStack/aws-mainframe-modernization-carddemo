"""Post-load verification: prove a load landed rather than assume it did.

Purpose
-------
Make this directory a regular package and state what belongs in it: the three independent
checks the migration plan requires after a load -- row counts per dataset, record checksums,
and money-total parity against the source files. Each answers a different failure, which is why
there are three and not one.

WHY (Assumptions)
-----------------
The three checks are deliberately not collapsed into a single "verify" routine, because each
catches a class the others miss. A row count catches a truncated or duplicated load and cannot
see a corrupted field. A checksum catches a corrupted field and cannot say which side is wrong
when the counts also differ. A money total catches a decode that shifted a sign or a decimal
point while the character count stayed right -- the failure mode this migration is most exposed
to, because a zoned-decimal sign overpunch read with the wrong convention produces a plausible
number rather than an error. The plan's own words are that a load which "succeeded" without a
money-total check is not evidence of anything.

WHY (Trade-offs)
----------------
Every routine here refuses to render a field value. That makes a failure less immediately
diagnosable -- an operator sees which record and which field differ, not what the two values
were -- and it is the right trade: these datasets carry primary account numbers and national
identifiers, and a verification log is retained and readable by every holder of log access. The
masked renderings the readers publish are the supported way to look at a record.
"""

from __future__ import annotations

__all__: list[str] = []
