"""Decode fixed-width CardDemo extracts into records, behind one package boundary.

Purpose
-------
``carddemo_migration.readers`` is the layer between the byte geometry of
:mod:`carddemo_migration.copybook` and the target-datastore loaders of
:mod:`carddemo_migration.loaders`. One module per record layout turns an extract file into
decoded records, and each module additionally publishes the privacy-safe renderings a
diagnostic may emit for the record it owns.

This module is the subpackage entry point. It declares no class, no function and no data of
its own, re-exports nothing, and decodes nothing; importing it reads no environment
variable, opens no dataset and reaches no network.

The modules
-----------
``account``
    The 300-byte account master of ``app/cpy/CVACT01Y.cpy``, its EBCDIC branch, and the
    masked record and field renderings for it.
``card``
    The 150-byte card master of ``app/cpy/CVACT02Y.cpy``, whose primary account number and
    card verification value are the most closely held values in the migration.
``tcatbal``
    The 50-byte transaction-category balance of ``app/cpy/CVTRA01Y.cpy``, keyed by account,
    transaction type and transaction category.

Why this file exists at all
---------------------------
Refactoring Rationale: this directory had no ``__init__.py``, which made
``carddemo_migration.readers`` an implicit NAMESPACE package while its two siblings
``copybook`` and ``loaders`` were regular packages. Measured rather than assumed: before this
file existed, ``importlib.import_module("carddemo_migration.readers").__file__`` was ``None``
where both siblings reported a real path. The asymmetry is the same one this distribution
already corrected once for ``copybook``, and ``pyproject.toml`` records the reasoning at that
site: setuptools discovery finds a regular package by its ``__init__.py``, so a namespace
child is included in a built artifact only incidentally.

Assumptions: that incidental inclusion is precisely what this distribution's ``src`` layout
exists to catch. ``pyproject.toml`` deliberately puts NO source directory on pytest's import
path so that the import the suite exercises is the INSTALLED distribution rather than the
checkout -- the stated purpose being that "a subpackage left out of the built artifact passes
every test and fails only when the container starts". A namespace subpackage weakens that
guarantee for the one layer that owns every record layout, because it is included by accident
rather than by declaration.

Trade-offs: the alternative was to leave the directory as a namespace package on the measured
evidence that the wheel currently contains its modules anyway. That evidence is correct and it
is also exactly the reasoning ``pyproject.toml`` records as having "held only while nothing
else changed" when it was applied to ``copybook``. One file with a docstring is a smaller cost
than a packaging failure that first appears in a container, so the same conclusion is reached
here for the same reason.

Assumptions: this docstring is also a Rule 1 obligation and not only a packaging fix. The
pydocstyle family selected in ``pyproject.toml`` raises D104 for a package without one, and
that file records the ``__init__.py`` exemption from D104 as "the single least defensible one
available" -- so a new regular package arrives with its purpose stated rather than with an
exemption.
"""
