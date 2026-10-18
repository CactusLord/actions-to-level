# Actions To Level

Tells you how many more of an item — or how many more actions — you need to reach your next level, based on the exact thing you're working with.

## Features

**While you skill.** Every XP drop is matched to the item that caused it: the ore or logs you just got, the fish you caught, the shark you cooked, the bones you buried, the logs you burned, the bars you smithed. An overlay keeps the countdown:

```
Actions to level
Iron ore                  124
Mining 41 > 42          35 xp
```

With a full inventory, or for things with no item (agility obstacles, pickpocketing), the XP is credited to the rock, tree, NPC or obstacle you last clicked.

**On right-click.** Right-click an item, rock, tree, fishing spot, stall or NPC and choose **Actions to level** (just under Examine). It lists every skill that thing trains:

```
Yew logs: 163 more to Woodcutting 61 (175 xp each)
Yew logs: 53 more to Firemaking 51 (202.5 xp each) - needs level 60
Raw shark: 1,265 more to Fishing 77 (110 xp each)
Raw shark: 984 more to Cooking 81 (210 xp each)
Iron rocks (Iron ore): 122 more to Mining 42 (35 xp each)
Master Farmer: 92 more to Thieving 41 (43 xp each)
```

**It learns your real rates.** The plugin remembers the XP you actually get per item and per object/NPC. After two matching drops it uses your real rate, so outfit bonuses, altars and other boosts are counted. Things the base table can't know (fishing spots, Motherlode pay-dirt, agility obstacles, alching) work after you've done them once. Values marked "measured" are your learned rates.

## Settings

| Section | Setting | Default |
|---|---|---|
| Display | Show overlay / Items shown / Hide after (minutes) | On / 3 / 5 |
| Display | Chat message on each XP drop | Off |
| Right-click option | On items / On objects / On NPCs | On |
| Right-click option | Only when XP is known / Require Shift | On / Off |
| Goal | Target level (0 = next level) / Virtual levels past 99 | 0 / Off |
| Tracking | Learn my real XP rates / Ignore combat XP | On / On |

Right-click the overlay and choose **Clear** to empty it.

## Building from source

Requires JDK 11+. Run `./gradlew run` (Windows: `gradlew.bat run`) to open a RuneLite developer client with the plugin loaded. Jagex Account users should follow https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts first.

## Credits

Base XP values come from RuneLite's Skill Calculator data (Copyright (c) 2021, Jordan Atwood), included in `XpData.java` under the BSD 2-Clause License.

## License

BSD 2-Clause. See `LICENSE`.
