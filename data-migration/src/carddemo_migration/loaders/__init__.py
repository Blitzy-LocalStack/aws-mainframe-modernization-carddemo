"""Load decoded CardDemo records into target datastores.

Purpose
-------
Provide the package boundary for Aurora and S3 loaders. The S3 staging loader
also owns logical generation cleanup because the AAP's ``dt=/gen=`` convention
makes each generation a distinct object-key prefix rather than an S3 object
version.
"""
