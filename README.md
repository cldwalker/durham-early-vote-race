## Description

This is a fun little webpage to compare early voting years as an animated race. Hopefully it encourages folks to vote early, pay attention to voter turn out and help others with voting!

## Sharing with Others

* https://tagaholic.me/durham-early-vote-race?lang=es - Shows website in Spanish by default
* https://tagaholic.me/durham-early-vote-race?view=data - Shows data by default
* https://tagaholic.me/durham-early-vote-race/#early-voting-links - Jumps right to early voting links

## Development

Recommend installing node 26.0+ and npm 11.12.1+. Then:

```sh
npm install
npm run build
# View in the browser
open index.html
# OR to view the youtube embed
python3 -m http.server &; open http://localhost:8000
```

The webpage should render well on Chrome and Firefox on either desktop or mobile.

All of the JS is written with https://github.com/squint-cljs/squint. This is the way.

cljs code should lint with [clj-kondo](https://github.com/clj-kondo/clj-kondo) - `clj-kondo --lint src`.

There are some dev tasks that use https://github.com/babashka/babashka. Run `bb tasks` to see what's
available.

## Contributing

I'm not looking for major contributions as this is mostly done. Contributions like more early voting links, adding your line to voter-phrases.csv and some cool art are welcome.

## LLM Disclaimer

This webpage was largely built by LLMs. I just did the "boring" parts like play with the idea and UX, get relevant data and links, write or review all visible text, etc.

## LICENSE

See LICENSE.md
