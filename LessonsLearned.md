# ProxyX — Lessons Learned

Notes on what was learned while building ProxyX, milestone by milestone. This
reinforces understanding and makes future maintenance easier.

## Milestone -1 — Project Foundation

- Git is a version-control tool (an "undo history" for the whole project);
  GitHub is the website that hosts Git repositories online. Related, but not the
  same thing.
- A repository ("repo") is one project's folder tracked by Git. A private repo is
  visible only to me.
- A commit is a saved snapshot of a change, stored in the project's history.
- A package name is a globally unique ID for the app (reverse-domain style). It
  cannot contain hyphens and should stay stable, because changing it later is
  painful.
- Semantic Versioning uses MAJOR.MINOR.PATCH; 0.x.x means early development and
  1.0.0 is the first stable release.
- Good projects start with a foundation — clear docs, standards, and a decision
  log — before any code is written.
