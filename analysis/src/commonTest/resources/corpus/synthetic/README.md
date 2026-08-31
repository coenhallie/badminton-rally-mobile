Hand-authored, NOT captured from the cloud.

This fixture exists to exercise the parser in CorpusFixture.kt: the key
names, the nesting, the null handling. It is deliberately not used by the
Phase 1 golden test, because comparing the Kotlin pipeline against numbers
this repo made up would pin the port against itself and prove nothing.

The golden test reads a fixture named `sample`, which has to come from
tools/corpus/fetch_corpus.py followed by trim_corpus.py against a real
processed video.
