# ADR-001 — Final Application ID and Migration

**Status:** Pending owner decision before dependent package/schema work

R16 will use a final Shippy application ID and an explicit old-package export /
new-package import path, as recommended by the master specification. The exact
reverse-domain string must be selected by the owner; it will not be invented by
the implementation agent.

Until selected, the current installed identity remains `org.oxycblt.auxio`
(`org.oxycblt.auxio.debug` for debug builds).
