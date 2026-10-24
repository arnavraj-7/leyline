# Field tests

Real phones, real Bluetooth, no internet. Numbers below come from each phone's own
Room database (pulled with `adb run-as`), so timings measured on one phone's clock
are exact; cross-phone timings include clock skew of a few seconds and are not used.

## 2026-10-19 · three phones, one room

| Phone | Model | Android | Node |
|---|---|---|---|
| A | vivo 1723 | 9 | E6CA6488 ("Phone-6488") |
| B | motorola signature | 16 | 25CAC98D ("motorolla") |
| C | OPPO CPH2293 | 13 | 71F98A6E ("Phone-8A6E") |

All three on protocol v2 (signed packets, `requireSignatures = true`), relay on, TTL 4.
To force multi-hop in one room, A and C blocked each other in topology mode, so
the only path between them was through B.

| # | Test | Expected | Result |
|---|---|---|---|
| 1 | C holds SOS; A and B linked directly | Both show it with "Signature verified"; C gets 2 receipts | Pass |
| 2 | B sends a fake SOS claiming to be C ("fire at the north gate"), signed with B's key | A and C drop it as WRONG_ORIGIN, show it under Mesh → Security, never in messages | Pass, both phones |
| 3 | A → everyone, text, A/C blocked | C receives it, "2 hops · via motorolla" | Pass |
| 4 | A holds SOS, A/C blocked | C gets it over 2 hops, signature verified; A gets receipts from B (1 hop) and C (2 hops) | Pass, receipt round trip 431 ms |
| 5 | C's app force-stopped; A sends; C restarted 19 s later | B stores the message and hands it over when C reappears | Pass, delivered ~1 s after C's app started |
| 6 | B's relay switched off; A sends | Reaches B only, never C | Pass |
| 7 | B and C screens locked; A sends text, then SOS | C receives both in the background; SOS raises a high-priority notification "SOS from Phone-6488 · 2 hops" | Pass, SOS receipt round trip 848 ms |

### Round trips measured on the sender's clock

| Message | Receipt from | Hops | Send → receipt back |
|---|---|---|---|
| C's SOS (test 1) | motorolla | 1 | 284 ms |
| C's SOS (test 1) | Phone-6488 | 1 | 324 ms |
| A's SOS (test 4) | motorolla | 1 | 265 ms |
| A's SOS (test 4) | Phone-8A6E | 2 | 431 ms |
| A's SOS, B and C locked (test 7) | motorolla | 1 | 602 ms |
| A's SOS, B and C locked (test 7) | Phone-8A6E | 2 | 848 ms |

A receipt round trip covers the message going out and the receipt coming back,
so one-way delivery over two hops was under half a second with screens on.

### Notes

- Plain broadcast text asks for no receipts by design (only SOS and direct
  messages do), so test 3 shows "Handed to 1 phone" on A and no receipt list.
- With relay off, B does not store other phones' messages either, so turning
  relay back on does not release the message from test 6. That is intended:
  relay off means "don't carry for others".
- Phones that last ran the v1 build send unsigned v1 packets, which v2 phones
  reject as malformed until updated.

### Demo mode run, same three phones (17:44)

A held SOS again with A/C still blocked. B's packet log (B's clock, so the gaps are exact):

| B's clock | Decision |
|---|---|
| 17:44:17.480 | Got A's SOS, direct, TTL 4, signature verified |
| 17:44:17.617 | Sent its own receipt to A |
| 17:44:17.620 | Relayed A's SOS to C with TTL 3 |
| 17:44:17.770 | Relayed C's receipt back to A with TTL 2 |

B turned the SOS around in 140 ms, and C's receipt came back through it 150 ms
later. On A, the route sheet showed receipts from B (direct, 429 ms round trip)
and C (2 hops, 461 ms round trip). The SOS was created at 17:44:12.903 on A's clock,
which is 4.6 s behind B's: an example of why cross-phone times are not used.
