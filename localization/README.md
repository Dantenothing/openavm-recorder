# OpenAVM UI languages

Both applications share six offline UI options: English, Simplified Chinese, Traditional Chinese, Thai, Vietnamese and Arabic. Follow system selects a supported language by language/script/region, falling back to English. Existing `system`, `zh-CN` and `en` preferences remain compatible.

`catalog/source.json` contains English/Chinese messages and source references. `extra.json` lists labels passed indirectly through navigation/category models. The four additional catalogs are UTF-8 JSON. Their initial drafts were machine-assisted; `terminology.json` provides reviewed core actions, camera direction terms and corrections. Native-speaker review remains useful for idiom and regional wording; no native-speaker review is claimed.

Run `python localization/generate.py` from the project root after editing translations. It validates every message and numbered placeholder and emits `src/main/resources/openavm-i18n/*.properties`. These resources are bundled in both APKs. Translation services, models and translation network calls are not part of the apps.

Message keys hash the pair `(English, Simplified Chinese)`, so different meanings of the same English word do not collide. Dynamic text uses explicit `{0}`, `{1}` arguments; translated text cannot execute format directives. Arabic arguments are isolated to preserve filenames and numbers. UI dates follow the selected app language; filenames, protocol identifiers, developer evidence and stable grouping keys retain their original formats.

Language switches update Compose state and persist the choice. They do not recreate the recording service. Arabic uses RTL in both app themes; physical camera slots, playback time and waveform selection keep LTR coordinates. User-supplied camera labels, filenames and raw technical error codes are not translated.

Validation includes catalog availability on the runtime classpath, equal key coverage, exact placeholder sets, six-language resolution, Chinese script/region handling, fallback, RTL flags and safe argument substitution. Actual head-unit/phone rendering and native-speaker wording are part of release-candidate testing.
