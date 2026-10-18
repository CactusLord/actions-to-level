# Actions To Level

Tells you how many more items, actions or kills you need for your next level, what your goals need from your bank, and what's left to gather. Built with ironmen in mind: everything you need, you have to make or gather yourself, so the plugin keeps track of it for you.

## Skilling

**While you skill.** Every XP drop is matched to what caused it: the ore or logs you just got, the fish you caught, the shark you cooked, the bones you buried, the bow you strung. The overlay counts down, with a time estimate from your pace:

```
Actions to level
Iron ore              124 ~38m
Mining 41 > 42          35 xp
```

**Right-click or hover.** Right-click an item, rock, tree, fishing spot, stall or NPC and choose **Actions to level**, or hover over it (items by default; rocks, trees and monsters can be turned on):

```
Yew logs: 163 more to Woodcutting 61 (175 xp each)
Yew logs: 141 more to Firemaking 61 (202.5 xp each)
  You have 344 (340 banked): Firemaking to 62 (25,197 xp short of 63)
Yew longbow: 913 more to Fletching 70 (75 xp each)
  Each uses 1 Yew longbow (u) (have 304) + 1 Bow string (have 14)
  You can make 14: Fletching stays 69 (limited by Bow string)
```

**Prayer altar maths.** For bones and ashes it also works out the gilded altar, Ectofuntus, Chaos altar (bones saved half the time) and Demonic offering, and what your banked bones get you.

## Goals (ironman)

Hold **Shift** and right-click something, then choose **Add goal**, or press **+** next to a skill in the side panel. A goal like "70 Fletching with yew longbows" shows:

```
Fletching 70: Yew longbow
913 left, 75 xp each, 68,426 xp, ~2h 10m
  Bow string: need 913, have 14, missing 899 (make with Crafting)
      - Flax: need 899, have 102, missing 797
  Yew longbow (u): need 913, have 304, missing 609 (make with Fletching)
      - Yew logs: need 609, have 344, missing 265 (Woodcutting 60)
```

All your goals add up into one **To gather** list, e.g. "Yew logs: 265 (Woodcutting 60, +46,375 xp)", so you know what to go and get. The overlay shows each goal and the first thing it's short of.

- **Your bank:** the plugin remembers it per account, so open it once.
- **Recipes:** learned by watching what you make. String one bow, and from then on it knows a yew longbow uses a yew longbow (u) and a bow string. Cooking, herb cleaning and gem cutting work from the start.

## Combat and Slayer

Right-click a monster to see kills to level for your current weapon and attack style. It uses the monster's hitpoints, learns your real XP per kill (bonus-XP monsters, spell XP), and adds Slayer:

```
Hellhound (116 hitpoints), Aggressive:
  Strength: 166 kills to 71 (464 xp each)
  Hitpoints: 606 kills to 73 (154.67 xp each)
  Slayer: 247 kills to 61 (116 xp each) - on task, 85 left
  Finishing your task (85 kills): Slayer stays 60 (...); Strength stays 70 (...)
  Other styles: Accurate Attack 166; Controlled Att 497, Str 497, Def 497; Defensive Defence 166
```

## Side panel

The sidebar button opens:
- **Search:** items (your bank first), skilling actions, and monsters you've fought, each with a **+** button to make it a goal.
- **Goals:** each with a target-level box.
- **To gather:** everything your goals are short of, added up.
- **Slayer task:** what finishing it gets you.
- **This session:** done, per hour, left, and time to level.

## Alerts

- **Almost levelled:** a notification when you're a few actions or kills from a level (10 by default).
- **Level-up unlocks:** on level up, a chat message with what you can now do and the next unlock.
- **Goal reached:** a notification when you hit one of your goals.

## Settings

Everything above can be switched on or off. Sections: Display, Goals and ironman, Right-click and hover, Alerts, Combat, Tracking.

## Building from source

Requires JDK 11-23. Run `./gradlew run` (Windows: `gradlew.bat run`) to open a RuneLite developer client with the plugin loaded. Jagex Account users should follow https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts first.

## Credits

- Base skilling XP values come from RuneLite's Skill Calculator data (Copyright (c) 2021, Jordan Atwood), included in `XpData.java` under the BSD 2-Clause License.
- Attack style detection follows RuneLite's Attack Styles plugin (Copyright (c) 2017, honeyhoney), BSD 2-Clause License.

## License

BSD 2-Clause. See `LICENSE`.
