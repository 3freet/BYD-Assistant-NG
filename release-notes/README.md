# Release notes

One file for each release, named after its tag: `v1.2.0-beta.5.md` for a beta, `v1.2.0.md` for a stable release. The file is
what the release page says, and what the app shows under *What's new* when it offers that build as an update. The release
workflows publish it **as written** and add one line under it, a link to everything that changed (*Full Changelog*), so the
file never contains that line.

- **Short and specific, one line for each change**, like a commit subject, so that each line reads well alone in the app.
  The major features and the important fixes since the previous build (the previous beta for a beta, the previous stable
  release for a stable one); small fixes and docs can be dropped, the Full Changelog link covers them.
- **Start a file** with `tools/new_release_notes.sh beta` (or `stable`). It writes the file for the next release with the
  commits since the previous release as bullets, which is what the build used to publish on its own, under a comment. Read
  the lines, keep them, reword them or drop some, show the result to the maintainer, then delete the comment: that deletion
  is the approval, and the check refuses the file while the comment is there.
- **Push it**: adding the file for the next beta to `main` is what starts the beta build (see
  [CONTRIBUTING.md](../CONTRIBUTING.md)). The page that is published is this file, as it is.
- **Check them** with `tools/check_release_notes.sh`. It rejects what the app cannot show (HTML, tables, code blocks, images),
  what does not belong on a public release page (network addresses, e-mail addresses, paths on a computer) and a template that
  was never approved. CI runs it on every push.
- **Fix the notes of a published release** by editing its file on `main` and running *Build Beta* by hand with `notes_only` and
  the tag: the notes go onto the existing release, nothing is built.
