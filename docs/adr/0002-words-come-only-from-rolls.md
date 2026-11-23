# Words come only from Rolls

Every Word is chosen by a Roll of six-sided dice resolved against the Word List, in both random mode (the secure random generator rolls each die individually, uniform 1–6 via rejection sampling) and Dice Mode (the user enters physical dice). We never pick a Word by drawing one random index into the Word List, even though a uniform index is mathematically equivalent and simpler. This keeps the app faithful to the Diceware method: one generation path for both modes, Rolls a user can check against the printed list, and nothing a future contributor can "optimise" away into a different algorithm.

## Consequences

- Every Word List must have exactly 6ⁿ Words (n ≥ 4, no upper bound) so every Roll maps to exactly one Word. Large lists mean import, validation and Roll lookup must work without holding the whole list in memory.
- Digits and symbols are also chosen by two-dice Rolls in both modes.
