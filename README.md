# Flip Copilot — RuneLite plugin

Runs the OSRS Copilot flipping loop (`flipping/README.md` method v1) inside the client, on live
[OSRS Wiki real-time prices](https://prices.runescape.wiki). Purely advisory: draws overlays and a side panel,
never sends input to the game.

| Feature | What it does | Where |
|---------|--------------|-------|
| **Scanner** | Port of `tools/osrs.py flip --robust`: margin after tax, per-side turnover model, units capped to a 30-min cycle, ranked by gp/hr; guards for stale quotes, spikes/crashes, ROI > 15%, thin flow; robust re-score on the **median 3h margin** + 7-day drift block | Side panel → *Top flips* |
| **Offer-setup overlay** | While you create a GE offer: **Place @** (buy = instant-sell price; sell = instant-buy price nudged off multiples of 50), buyers/sellers, tax, break-even, margin + ROI, med-3h margin, per-side flow, fill estimate for the quantity typed, buy limit + reset time, 24h/5m/7d moves, and a colour grade of the price you typed (green = optimal, yellow = under midpoint, red = overpaying / above buyers / below break-even) | Top-left overlay when the GE setup screen is open |
| **Item lookup** | Sidebar search box (prefix matches first) or right-click **Flip lookup** on any item in the inventory / bank / GE side panel: buy @, sell @, margin, med-3h margin, break-even, flows, cycle estimate for your budget, gp/hr, buy limit + reset, what you hold, guards | Side panel → *Item lookup* |
| **Slot advisor** | The playbook clocks on every open offer, with **wall-clock deadlines**: BUY → 0 filled after 15 min: +1 gp (bulk) / +0.5% (high value), never past the midpoint; < 50 % after 30 min: cancel + sell what filled; filled in seconds: "overpaid?". SELL → above buyers & 0 sold after 20 min: relist at buyer price → −1 % → break-even; at break-even 30 min: dump | Bottom-left overlay + side panel → *Slots – do now*; RuneLite notification when a clock fires |
| **Cost basis + ledger** | Tracks every fill so break-even and P&L are real, not guessed; realized total in the panel; appends `~/.runelite/flip-copilot/ledger.csv` (same columns as `flipping/ledger.csv`) | Panel → *Holding* |

## Build / install

```
./gradlew test          # unit tests: tax maths, scanner scoring, clocks, cost basis
./sideload.sh           # jar → ~/.runelite/sideloaded-plugins/flip-copilot.jar
../tools/runelite_dev.sh   # start RuneLite in developer mode (loads sideloaded plugins)
```
`./gradlew run` launches a dev client with the plugin loaded from source.

## Config (Flip Copilot → settings)

- **Budget & scanning** — budget gp, *use inventory coins* (default on), scan interval, robust top-N (API calls: 4 per scan + 2 per re-scored item), rows shown, F2P only.
- **Scanner filters** — mirror the CLI flags: min price, quote age, 1h side volume, 24h volume, max ROI, max 24h/7d move, volume share, max cycle.
- **Order clocks** — buy raise / cancel minutes, sell step / dump minutes, bulk threshold, notifications.
- **Display** — toggle each overlay, "only when due", right-click lookup, time zone for clocks (blank = system; the player's is `Europe/Berlin`).

## Files (`~/.runelite/flip-copilot/`)

| File | Purpose |
|------|---------|
| `mapping.json` | wiki item mapping cache (24 h) |
| `offers.json` | per-slot clocks (survive a client restart) |
| `positions.json` | units held from tracked buys, avg cost, realized P&L |
| `ledger.csv` | every fill: `date_utc,item,side,qty,price,tax_per_unit,total_net,est_margin,note` |

## Notes / limits

- Break-even uses exact floored-tax maths (`net(p) = p − ⌊p/50⌋`); the CLI's `ceil(buy/0.98)` is 1 gp conservative.
- Tax-exempt items (bonds, some low-tier food/ammo/tools) are taxed as normal items here — verify on the wiki if it matters.
- Buy-limit reset times come from the core Grand Exchange plugin's saved timers (keep it enabled).
- Offers already open when the plugin starts get their clock started at that moment (conservative), not at the real placement time.
- Data source is the wiki prices API (the same one RuneLite's own price lookups use); please keep the scan interval sane.
