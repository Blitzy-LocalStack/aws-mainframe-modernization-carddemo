"""Verify that only proven single-byte EBCDIC code pages can decode a CardDemo field.

The module under test converts the reference extracts from mainframe bytes to characters one
fixed-width field at a time. Its ``code_page`` parameter is the one input a caller can get wrong
WITHOUT being told, because a wrong-but-registered single-byte page decodes a field to the same
width and changes only its content: a balance comes back plausible and wrong rather than refused.
These tests pin the three defences that close that hole -- a curated allow-list of four audited
page names, a per-page proof that runs before the first byte is decoded through a page, and a
one-character-per-byte post-condition on every span decoded afterwards -- so none of them can be
relaxed silently.
"""

from __future__ import annotations

import codecs

import pytest

from carddemo_migration.copybook import ebcdic_codec
from carddemo_migration.copybook.ebcdic_codec import (
    EBCDIC_CODE_PAGE,
    SUPPORTED_CODE_PAGES,
    EbcdicFieldDecodeError,
    decode_field_characters,
)
from carddemo_migration.copybook.layouts import FieldSpec, Kind

# WHY : Assumptions: one narrow descriptor serves every case here rather than a per-test layout,
#       because none of these assertions is about geometry -- they are about which mapping is
#       allowed to be applied to a span. Ten characters is the width of the account-status and
#       date fields these extracts carry, so the span is representative without being large enough
#       to obscure a failure message.
_TEXT_FIELD = FieldSpec(name="TEST-TEXT", start=0, length=10, kind=Kind.TEXT)

# WHY : Assumptions: the record is EBCDIC bytes rather than an ASCII literal, so the decode under
#       test has real work to do. 0xC1 0xC2 0xC3 is "ABC" in every Latin EBCDIC page and 0x40 is
#       the pad byte, so a correct decode yields "ABC" followed by seven spaces -- and an
#       ASCII-derived page yields something visibly different rather than something subtly wrong.
_EBCDIC_RECORD = bytes([0xC1, 0xC2, 0xC3, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40])

# WHY : Refactoring Rationale: these are the pages a reader actually reaches for when a decode
#       fails, which is why they are named individually rather than represented by one example.
#       ``latin-1`` is the dangerous case and the reason this suite exists: it is registered, it is
#       single byte, and it decodes all 256 values without raising, so before the allow-list it
#       would have returned ten characters of the declared width with every letter and digit wrong.
#       ``cp1252`` and the Unicode pages fail differently -- they raise on some byte values -- and
#       are included so the refusal is proven to be the allow-list's decision rather than a
#       decode error that happens to look like one.
_REJECTED_REGISTERED_PAGES = ("latin-1", "iso8859-1", "cp1252", "utf-8", "utf-16", "ascii")

# WHY : Assumptions: every name here IS an EBCDIC-family page the optional distribution
#       registers, and each is refused anyway, which is the property worth pinning: membership is
#       decided by audit and not by the name looking mainframe-shaped. Each was measured on this
#       interpreter and each fails for a DIFFERENT reason, which is why five are named rather than
#       one: ``cp273`` and ``cp1026`` are total and bijective and round-trip exactly, yet put
#       national characters where the sign-overpunch zones belong (0xC0 decodes to "a" and "c"
#       with diacritics rather than to "{"), so a signed amount read through either would carry a
#       wrong sign; ``cp1141`` maps both 0x15 and 0x25 onto one character, so its 256 byte values
#       yield 255 distinct characters and it cannot round-trip at all; ``cp424`` cannot decode all
#       256 byte values without raising; ``cp290`` is the Japanese katakana page and collapses 27
#       byte values. A name-only allow-list built from the family prefix would have admitted all
#       five, and a CardDemo amount decoded through any of them is silently wrong.
_REJECTED_EBCDIC_FAMILY_PAGES = ("cp273", "cp1026", "cp1141", "cp424", "cp290")


def test_the_default_page_is_admitted_and_decodes_one_character_per_byte() -> None:
    """The module's own page is in the verified set and preserves the field's width."""
    assert EBCDIC_CODE_PAGE in SUPPORTED_CODE_PAGES

    decoded = decode_field_characters(_EBCDIC_RECORD, _TEXT_FIELD)

    assert decoded == "ABC       "
    assert len(decoded) == _TEXT_FIELD.length


def test_every_admitted_page_maps_the_ebcdic_digit_and_pad_bands() -> None:
    """Each published page decodes the pad byte and the digit band as EBCDIC requires."""
    # WHY : Assumptions: the published constant is asserted to BE what it claims rather than
    #       merely to be non-empty, because the set is derived at import and a derivation that
    #       admitted a wrong page would still produce a plausible-looking frozenset. Re-proving
    #       the invariant here means the test fails on the derivation, not only on a decode.
    assert SUPPORTED_CODE_PAGES
    for page in sorted(SUPPORTED_CODE_PAGES):
        codec = codecs.lookup(page)
        assert codec.decode(bytes([0x40]), "strict")[0] == " ", page
        assert codec.decode(bytes(range(0xF0, 0xFA)), "strict")[0] == "0123456789", page


@pytest.mark.parametrize("page", _REJECTED_REGISTERED_PAGES)
def test_a_registered_non_ebcdic_page_is_refused_rather_than_applied(page: str) -> None:
    """A page outside the verified set is refused, however ordinary its name."""
    assert page not in SUPPORTED_CODE_PAGES

    with pytest.raises(EbcdicFieldDecodeError) as refusal:
        decode_field_characters(_EBCDIC_RECORD, _TEXT_FIELD, code_page=page)

    # WHY : Assumptions: the message is asserted to name the verified set, not merely to exist.
    #       The plausible mistake is supplying a page that IS registered and IS single byte, so a
    #       refusal that does not say which pages ARE acceptable leaves the caller unable to tell
    #       whether the remedy is to install something or to stop.
    assert EBCDIC_CODE_PAGE in str(refusal.value)


@pytest.mark.parametrize("page", _REJECTED_EBCDIC_FAMILY_PAGES)
def test_an_ebcdic_family_page_outside_the_audited_set_is_refused(page: str) -> None:
    """A page from the EBCDIC family is refused too: belonging to the family is not the test."""
    # WHY : Assumptions: the refusal proved here is the NAME check rather than the page proof,
    #       because the name check runs first and none of these pages is named in the audited set.
    #       That ordering is the point of the case: the family prefix is the intuition a caller
    #       reaches for, and the module declines it. The measurement that each of these would ALSO
    #       fail the proof if it were named is recorded on the constant above, and the anchor half
    #       of that proof is exercised directly by the overpunch case further down.
    # WHY : Assumptions: the page is skipped rather than failed when the optional distribution is
    #       absent, because these names exist only when it is installed. Skipping keeps the suite
    #       meaningful on a bare checkout, which is the same importability guarantee the module
    #       under test makes about its own optional import.
    try:
        codecs.lookup(page)
    except LookupError:
        pytest.skip(f"code page {page} is registered only by the optional ebcdic distribution")

    assert page not in SUPPORTED_CODE_PAGES

    with pytest.raises(EbcdicFieldDecodeError):
        decode_field_characters(_EBCDIC_RECORD, _TEXT_FIELD, code_page=page)


def test_the_admitted_set_is_the_four_audited_pages() -> None:
    """The allow-list is pinned by name, so widening it has to be a deliberate edit."""
    # WHY : Assumptions: the four names are asserted rather than counted, because a count would
    #       hold for any four pages and the identity of each one is what was audited. cp037 is the
    #       page the shipped extracts are in; cp1140 differs from it at exactly one byte value,
    #       cp500 at seven and cp1047 at eight, and not one of those differences touches the digit
    #       zone, either overpunch zone, the letter zone, the blank or the low value.
    assert SUPPORTED_CODE_PAGES == frozenset({"cp037", "cp500", "cp1047", "cp1140"})


def test_the_page_proof_refuses_a_bijective_page_that_moves_the_overpunch_zone() -> None:
    """The known-answer anchors are load-bearing: structural soundness alone is not enough."""
    # WHY : Refactoring Rationale: this case exists because an earlier revision of the module
    #       admitted a page set derived from structural probing alone, and fifteen of the pages it
    #       admitted decode 0xC0 and 0xD0 to national characters. Those two byte ranges are, letter
    #       for letter, the positive and negative sign-overpunch tables ``zoned`` reads, so a
    #       balance decoded through such a page loses its sign while keeping its width -- the exact
    #       plausible-wrong-value outcome this suite exists to prevent. Asserting the refusal here
    #       keeps the anchors from being dropped as redundant on the argument that the structural
    #       proof already establishes reversibility, which it does and which is not sufficient.
    # WHY : Trade-offs: the proof helper is called directly, reaching past the public decode entry
    #       point into a private function. That coupling is accepted because it is the only way to
    #       attribute the refusal to the ANCHORS: reached through the public entry point, cp273 is
    #       refused one step earlier for not being named in the audited set, and the assertion
    #       below would then hold for a reason that has nothing to do with the overpunch zone.
    try:
        codec = codecs.lookup("cp273")
    except LookupError:
        pytest.skip("code page cp273 is registered only by the optional ebcdic distribution")

    every_byte = bytes(range(256))
    decoded, consumed = codec.decode(every_byte, "strict")
    assert consumed == 256
    assert len(set(decoded)) == 256
    assert codec.encode(decoded, "strict")[0] == every_byte
    assert codec.decode(bytes(range(0xF0, 0xFA)), "strict")[0] == "0123456789"

    with pytest.raises(EbcdicFieldDecodeError) as refusal:
        ebcdic_codec._vet_code_page(codec, _TEXT_FIELD)

    assert "structurally complete but is not an EBCDIC page" in str(refusal.value)
    assert "sign-overpunch zone" in str(refusal.value)


def test_a_structurally_sound_page_outside_the_audit_is_still_refused() -> None:
    """Passing the proof is necessary and not sufficient: being audited is the gate."""
    # WHY : Refactoring Rationale: this omission is invisible from the module alone and was
    #       re-derived once already, so it is pinned here. Measured on this interpreter, cp1097
    #       satisfies every condition the page proof checks -- it decodes all 256 byte values, they
    #       are distinct, they re-encode exactly, and it answers every anchor including both
    #       overpunch zones -- so a reader who probes the registry finds FIVE qualifying pages and
    #       only four admitted. The fifth is excluded because no CardDemo extract is in it and
    #       nobody has audited its difference from cp037; an unaudited page is refused by policy
    #       rather than admitted by structure, and this case states that intent as a test.
    # WHY : Assumptions: the page is skipped rather than failed when the optional distribution is
    #       absent, matching the treatment of every other name that distribution alone registers.
    try:
        codec = codecs.lookup("cp1097")
    except LookupError:
        pytest.skip("code page cp1097 is registered only by the optional ebcdic distribution")

    ebcdic_codec._vet_code_page(codec, _TEXT_FIELD)

    assert "cp1097" not in SUPPORTED_CODE_PAGES
    with pytest.raises(EbcdicFieldDecodeError) as refusal:
        decode_field_characters(_EBCDIC_RECORD, _TEXT_FIELD, code_page="cp1097")
    assert "a page outside that set is" in str(refusal.value)


def test_an_unregistered_page_is_refused_before_any_byte_is_read() -> None:
    """A page name the interpreter does not know is this module's own error, not a LookupError."""
    with pytest.raises(EbcdicFieldDecodeError):
        decode_field_characters(_EBCDIC_RECORD, _TEXT_FIELD, code_page="carddemo-no-such-code-page")


def test_a_page_decoding_fewer_characters_than_bytes_is_refused() -> None:
    """The per-span one-character-per-byte post-condition fires even for a page that was proved."""
    # WHY : Refactoring Rationale: this is the only assertion that reaches the per-span length
    #       post-condition, and reaching it needs a page that PASSES the 256-byte page proof and
    #       is then lossy on a field span, because a page that failed the proof would be refused
    #       one step earlier and the refusal asserted below would never be the one raised. The
    #       substituted codec therefore delegates every input to the real page EXCEPT this test's
    #       own field span -- which is exactly the hazard the per-span checks exist for:
    #       a codec whose behaviour differs between the vector it was proved on and the span it is
    #       asked to decode. No real page does this, so it has to be constructed.
    # WHY : Trade-offs: the memoised proof result for the canonical name is dropped for the
    #       duration of the test and restored afterwards, reaching into the module's private cache
    #       to do it. That coupling is accepted because the cache is what makes substitution by
    #       name ineffective: without the drop, the resolver returns the codec proved earlier in
    #       the session and the substituted one is never consulted, so the test would pass while
    #       asserting nothing. Restoring the entry keeps the rest of the session on the real page.
    # WHY : Assumptions: the name is registered in its NORMALISED spelling, because the registry
    #       lowercases a requested name and replaces every non-alphanumeric character with an
    #       underscore before it reaches a search function. Registering the hyphenated spelling and
    #       looking it up would silently never match, and the test would then pass by raising the
    #       unregistered-page refusal instead of the length refusal it exists to prove.
    # WHY : Trade-offs: the search function is registered and then removed in a ``finally`` block,
    #       using ``codecs.unregister``, which both drops the function AND clears the registry's
    #       lookup cache. The cache clear is the load-bearing half: a lookup performed while the
    #       function was registered is memoised, so dropping the function without clearing would
    #       leave the lossy codec resolvable by name for the rest of the process and could make a
    #       later test in the same session decode through it.
    requested_name = "carddemo-lossy-probe"
    normalised_name = "carddemo_lossy_probe"
    genuine = codecs.lookup(EBCDIC_CODE_PAGE)

    def conditional_decode(raw: bytes, errors: str = "strict") -> tuple[str, int]:
        """Decode faithfully except on this test's own field span, which halves in length."""
        if bytes(raw) != _EBCDIC_RECORD:
            return genuine.decode(raw, errors)
        return "x" * (len(raw) // 2), len(raw)

    def search(name: str) -> codecs.CodecInfo | None:
        """Serve the conditionally lossy codec under a name whose canonical form is admitted."""
        if name != normalised_name:
            return None
        return codecs.CodecInfo(
            encode=genuine.encode,
            decode=conditional_decode,
            name=EBCDIC_CODE_PAGE,
        )

    codecs.register(search)
    remembered = ebcdic_codec._VETTED_CODECS.pop(EBCDIC_CODE_PAGE, None)
    try:
        assert codecs.lookup(requested_name).name in SUPPORTED_CODE_PAGES

        with pytest.raises(EbcdicFieldDecodeError) as refusal:
            decode_field_characters(_EBCDIC_RECORD, _TEXT_FIELD, code_page=requested_name)

        # WHY : Assumptions: the phrase asserted here is the module's own wording for the
        #       post-condition, and the two counts are asserted beside it so the assertion pins
        #       the refusal to THIS field and THIS decode rather than to any length complaint the
        #       module might raise. A substring alone would pass for an unrelated refusal.
        refusal_text = str(refusal.value)
        assert "not behaving as a single-byte page" in refusal_text
        assert f"decoded the {_TEXT_FIELD.length} bytes this field declares" in refusal_text
        assert f"into {_TEXT_FIELD.length // 2} characters" in refusal_text
    finally:
        codecs.unregister(search)
        if remembered is not None:
            ebcdic_codec._VETTED_CODECS[EBCDIC_CODE_PAGE] = remembered
        else:
            ebcdic_codec._VETTED_CODECS.pop(EBCDIC_CODE_PAGE, None)
