"""Describe and decode the fixed-width record layouts the CardDemo extracts carry.

Purpose
-------
Provide the package boundary for the copybook layer: :mod:`layouts` holds the field
geometry of every dataset in the migration contract, and :mod:`zoned` decodes and
encodes the zoned-decimal spans that geometry describes. Both are deliberately
standard-library-only, so a caller can read a record layout without a database
driver or an AWS SDK on the path.

Assumptions: this file exists to make the subpackage a regular package rather than an
implicit namespace package, which is what its sibling ``loaders`` already is. The
asymmetry was previously left in place on the evidence that the built wheel included
``carddemo_migration/copybook/layouts.py`` anyway - correct at the time, and the
reason the omission was not urgent. It is closed here because that evidence only
holds while nothing else changes: setuptools' package discovery finds a regular
package by its ``__init__.py`` and an implicit namespace child only by falling back
to namespace discovery, so a future ``packages`` or ``exclude`` entry, or a build
backend that declines implicit namespaces, would drop this directory from the
distribution while every test in the checkout continued to pass. The failure would
surface as ``ModuleNotFoundError: carddemo_migration.copybook`` inside a container,
which is the worst place to learn that a package was never shipped.

Trade-offs: the file carries no re-exports and deliberately imports nothing. Hoisting
``layouts`` and ``zoned`` symbols to this level would give callers a shorter import,
but it would also make importing the package for a layout descriptor pull in the
decoder and vice versa, and it would create a second spelling for every name the two
modules already export through their own ``__all__``. Callers therefore import
``carddemo_migration.copybook.layouts`` and ``carddemo_migration.copybook.zoned``
directly, which is the form the readers and the loaders are written against.
"""
