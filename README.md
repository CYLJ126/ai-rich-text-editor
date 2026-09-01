# ARTE

[English](README.md) | [简体中文](README.zh-CN.md)

ARTE (AI Rich Text Editor) is an AI-assisted rich-text editing project with a
Java backend and a web frontend.

## Repository layout

- `backend/`: Java 21 multi-module Maven project.
- `frontend/`: Web client.
- `deploy/`: Container and database deployment files.

See [backend/README.md](backend/README.md) and [deploy/DOCKER_DEPLOY.md](deploy/DOCKER_DEPLOY.md)
for the available backend and deployment notes.

## Security before deployment

Copy example configuration files to local, ignored files and replace every
placeholder/default credential before exposing an instance to a network. Do not
commit API keys, database passwords, JWT keys, encryption keys, or `.env` files.

Security issues should be reported according to [SECURITY.md](SECURITY.md).

## License

Unless a file or directory says otherwise, the portions of this repository for
which the ARTE copyright holders have the right to grant a license are available
under the [MIT License](LICENSE), and the third-party libraries, models, materials 
and deployment components are applicable to their respective licenses.

The MIT License does **not** replace or override the licenses, copyright notices,
or attribution requirements of third-party code, libraries, assets, models, or
services. In particular, some backend source files retain Apache-2.0 notices from
their upstream project, and runtime dependencies remain under their respective
licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LICENSES/](LICENSES/).

Before publishing a release artifact, regenerate and review a complete dependency
and license inventory for the exact resolved versions. The checked-in notice is a
human-maintained summary, not a substitute for that release-time audit.

