# Actions To Level

Tells you how many more of an item, how many more actions, or how many more kills you need to reach your next level, based on exactly what you're doing.

## Skilling

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
Iron rocks (Iron ore): 122 more to Mining 42 (35 xp each)
Master Farmer: 92 more to Thieving 41 (43 xp each)
```

## Combat and Slayer

Right-click a monster and choose **Actions to level**. It uses the monster's hitpoints and your current weapon and attack style to work out the XP each kill gives, and how many kills you need:

```
Hellhound (116 hitpoints), Aggressive:
  Strength: 166 kills to 71 (464 xp each)
  Hitpoints: 606 kills to 73 (154.67 xp each)
  Slayer: 247 kills to 61 (116 xp each) - on task, 85 left
```

- Controlled splits the XP between Attack, Strength and Defence; Longrange and defensive casting split it with Defence.
- Slayer XP only counts on task. The line tells you whether the monster is your current task and how many are left.
- As you kill, the overlay counts down kills for the skills your style trains, plus Slayer while on task.

## It learns your real rates

The plugin remembers the XP you actually get, per item, per object or NPC, and per monster and attack style. After two matching drops it uses your real rate, so these are counted:
- outfit bonuses and altars
- monsters with bonus combat XP
- spell XP when you're maging

Things the base data can't know work after you've done them once: fishing spots, Motherlode pay-dirt, agility obstacles, and monsters with unknown hitpoints. Values marked "measured" are your learned rates.

## Settings

| Section | Setting | Default |
|---|---|---|
| Display | Show overlay / Items shown / Hide after (minutes) | On / 4 / 5 |
| Display | Chat message on each XP drop | Off |
| Right-click option | On items / On objects / On NPCs | On |
| Right-click option | Only when XP is known / Require Shift | On / Off |
| Goal | Target level (0 = next level) / Virtual levels past 99 | 0 / Off |
| Combat | Track kills / Show Slayer / Show Hitpoints on overlay | On / On / Off |
| Tracking | Learn my real XP rates | On |

Right-click the overlay and choose **Clear** to empty it. Slayer task details come from RuneLite's built-in Slayer plugin.

## Building from source

Requires JDK 11-23. Run `./gradlew run` (Windows: `gradlew.bat run`) to open a RuneLite developer client with the plugin loaded. Jagex Account users should follow https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts first.

## Credits

- Base skilling XP values come from RuneLite's Skill Calculator data (Copyright (c) 2021, Jordan Atwood), included in `XpData.java` under the BSD 2-Clause License.
- Attack style detection follows RuneLite's Attack Styles plugin (Copyright (c) 2017, honeyhoney), BSD 2-Clause License.

## License

BSD 2-Clause. See `LICENSE`.
