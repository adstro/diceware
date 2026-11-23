# Diceware

Generates memorable, high-entropy passphrases by picking words uniformly at random from a word list.

## Language

**Passphrase**:
The generated sequence of Words shown to the user.
_Avoid_: Password, pass phrase

**Word**:
A single entry drawn from a word list to form part of a Passphrase.
_Avoid_: Token, term

**Word List**:
A named collection of unique Words that Passphrases are drawn from.
_Avoid_: Dictionary, wordlist, word file

**EFF Word List**:
One of the three Word Lists published by the Electronic Frontier Foundation (Large, Short #1, Short #2); Large is the default.

**Custom Word List**:
A Word List the user imported themselves, in contrast to an EFF Word List.
_Avoid_: User list, third-party list

**Selected Word List**:
The Word List currently used to generate Passphrases; the user's last choice persists across sessions.
_Avoid_: Active list, current dictionary

**Separator**:
The character placed between Words in a Passphrase (space by default).
_Avoid_: Delimiter, joiner

**Strength**:
The entropy of a Passphrase in bits, assuming an attacker knows the Word List and all settings.
_Avoid_: Entropy, security score

**Roll**:
The sequence of die results (each 1–6) that identifies one Word in a Word List; a Word List of 6ⁿ Words needs n dice per Roll. Every Word is chosen by a Roll, whether the dice are physical or rolled by the secure random generator.
_Avoid_: Dice key, index, number

**Dice Mode**:
Building a Passphrase from Rolls of physical dice entered by the user, instead of Rolls made by the app's secure random generator.
_Avoid_: Manual mode, real dice mode

**Word count**:
The number of Words in a Passphrase.
_Avoid_: Length, pass phrase length
