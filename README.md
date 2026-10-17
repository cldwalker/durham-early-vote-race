## Description

This is a fun little webpage to compare early voting years as an animated race. Hopefully it encourages folks to vote early, pay attention to voter turn out and help others with voting!

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

Most of the JS is written with https://github.com/squint-cljs/squint. This is the way.

cljs code should lint with [clj-kondo](https://github.com/clj-kondo/clj-kondo) - `clj-kondo --lint src`.

## Contributing

I'm not looking for major contributions as this is mostly done. Contributions like more early voting links, adding your line to voter-phrases.csv and some cool art are welcome.

## AI Disclaimer

This webpage was largely built by Claude. I just did the "boring" parts like play with the idea and UX, get relevant data and links, write or review all visible text, etc.

## LICENSE

See LICENSE.md