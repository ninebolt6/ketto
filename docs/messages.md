# Messages and Display Text

All messages are managed as MiniMessage templates in `lang/messages_<lang>.yml`.
Code builds a `Msg` holding a key and arguments, which is rendered in the
recipient's language at send time. The canonical list of keys and default text
lives in `MessageKeys` and the bundled `messages_ja.yml`/`messages_en.yml`.

## Language Selection

```yaml
# config.yml
language: auto # auto = each player's client locale. ja/en etc. fixes it server-wide
default-language: ja # fallback + shared surfaces like signs and scoreboard
```

- With `language: auto`, chat/broadcast resolve the language from
  `Player.locale()` (`ja_jp` → exact match → `ja` language-part match).
  Unsupported locales fall back to `default-language`
- Signs, scoreboard, and console are shared surfaces, so they render in a fixed
  language (the fixed `language` value, or `default-language` when auto)
- Language files live in `plugins/1vs1/lang/messages_<lang>.yml`. The bundled
  default language is the base and same-named files in dataFolder override it
  per key. A new language is added just by dropping in `messages_<lang>.yml`.
  Keys present in the default language but missing from a language are warned
  at startup and fall back to the default language at render time

## Mechanism

- `MessageKeys`: aggregates all keys. `MessagesTest` verifies key coverage
  across all bundled languages
- `Messages` factories (`joined(name)` etc.) only return a `Msg`; they do not
  render. `send(sender, msg)` renders in the recipient's locale and prepends
  the prefix
- Arguments come in two kinds: `Msg.Str` (plain text; `<` is never treated as
  a tag) and `Msg.Nested` (another Msg rendered in the same locale; used to
  embed state displays)
- Placeholders are MiniMessage tags such as `<name>`. The tag name in the
  template must match the `Str`/`Nested` name (unresolved placeholders render
  as-is)
