Copied unchanged from the visionnav repo, `contracts/examples/{local_odometry,health,map_fix}/`, at commit
c471d433fe82ae9c87ac107fe58357ac3cbdc451 (contracts v0.2). `ContractExamplesTest` checks that every `valid*.json`
parses and every `invalid_*.json` is rejected. When the contract changes, copy the new examples here.

Update WE-7: the `health/` examples are v0.4 (visionnav GNSS-Denied commit f7e9918, contracts/examples/health). Health 0.2 is no longer read.

Update NH-H3: the `health/` examples are v0.5 (visionnav GNSS-Denied commit 2606a62, contracts/examples/health): `auth` and `fc_acceptance` for fc_bridge. Health 0.3/0.4 are still read (covered by CompanionStateTest).
