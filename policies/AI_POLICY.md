# AI policy — Rolia

Rolia is a small project. Every patch in this repository has to be read by a human before it can be
trusted, because a wrong patch here does not throw an exception — it quietly changes what the world
generates, and that damage is not repairable after the fact.

So the rule is about **review**, not about tooling.

## Using an LLM is fine. Not reading its output is not.

You may use whatever tools you like to write code, issues or pull requests. What is not acceptable is
submitting something you have not read and cannot explain. Concretely, if you open a pull request you
should be able to answer:

- what the change does, in your own words;
- why it is correct — not "the model said so", but the actual reason;
- what it could break, and how you checked that it does not.

If you cannot answer those three, the change is not ready, regardless of how it was produced.

## What gets closed without discussion

- Issue reports describing behaviour that does not exist, generated wholesale and not verified
  against a running server.
- Pull requests whose description does not match the diff.
- Changes to `io/rolia/secureseed/**`, to the worldgen patches, or to `scripts/apply_dab_hooks.py`
  that come with no explanation of the effect on generated worlds. These are the parts where a
  plausible-looking mistake is most expensive and least visible.

## Why this is stricter for worldgen

Most bugs announce themselves. A change to the secret-seed pipeline does not: the server starts, the
world generates, everything looks normal, and the only symptom is that chunks generated before the
change no longer match chunks generated after it. There is no migration and no repair. That is why
this repository asks for an explanation rather than a passing build.
