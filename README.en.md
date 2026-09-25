# ARTE

[简体中文](README.md) | [English](README.en.md)

ARTE (AI Rich Text Editor) is a self-hostable rich-text editor and knowledge management project. It provides Markdown editing, mathematical notation and diagrams, document sharing and comments, version history, AI-assisted writing, and knowledge retrieval for technical documentation, product manuals, and team knowledge bases.

![Article list and document spaces](docs/public/assets/captures/editor/readme-overview.png)

## demo url

https://arte-demo.nas.haiqingd.top:1443/Writing/BasicWriting/?articleId=122
username: guest
password: arte@2026

## Markdown, formulas, and diagrams

The editor provides rich-text, Markdown source, and split-view modes, with format conversion, section navigation, find and replace, and save status.

- **Markdown and rich text:** Import, export, and edit Markdown alongside headings, lists, tasks, tables, and code blocks. Insert content with `/` commands. Complex rich-text styles and custom nodes have Markdown conversion limitations.
- **Mathematical notation:** Render inline and block LaTeX with KaTeX, preview formulas while editing, and reopen them for changes. Useful for algorithms, study notes, and technical explanations.
- **Mermaid diagrams:** Describe flowcharts and sequence diagrams in text and render them inside the article. Update the source as the process changes.
- **Draw.io integration:** Create architecture diagrams, flowcharts, and UML inside your writing workflow. Save a preview into the document and reopen the diagram to keep it up to date.

<details>
<summary>View Markdown, formula, and diagram screenshots</summary>

![Markdown source and rich-text conversion](docs/public/assets/captures/editor/md-rich_text-transform.png)

![LaTeX editor and preview](docs/public/assets/captures/editor/LaTeX.png)

![Mermaid sequence diagram](docs/public/assets/captures/editor/mermaid.png)

![Draw.io diagram preview](docs/public/assets/captures/editor/drawio-preview.png)

</details>

## Document organization and media

Organize articles in nested folders with drag-and-drop ordering, moving, and bulk operations. Personal, shared, and public spaces separate content access. Articles support covers, summaries, tags, and filtering by folder, author, or type.

Embed images, audio, video, attachments, and mind maps. Import Markdown files or ZIP archives with images, export Markdown, and publish or withdraw public content. The default deployment stores uploads in persistent volumes.

## Document collaboration and version history

- **Sharing and permissions:** Grant users or roles access to articles and folders, with permissions for reading, commenting, or editing. Organize content in personal, shared, and public spaces.
- **Comment threads:** Attach comments to selected text, reply, resolve or reopen a discussion, and navigate back to the highlighted passage.
- **Document history:** Browse versions with authors and timestamps, compare changes side by side, and navigate between differences to recover a passage or review an edit.
- **Concurrent edit awareness:** Version and conflict notifications help people maintain the same document. Live shared cursors and automatic merging of simultaneous edits are not currently supported.

<details>
<summary>View comments and version comparison</summary>

![Selection comments and replies](docs/public/assets/captures/article-manage/comment.png)

![Version comparison](docs/public/assets/captures/article-manage/version-compare.png)

</details>

## AI-assisted writing

- **Contextual completion:** Type `//` in a paragraph to request a continuation using nearby text and chapter context. Accept with `Tab` or `Enter`, or dismiss with `Esc`.
- **Prompted writing:** Open the AI writing slash command, describe the task, review the generated text, and insert it into your article.
- **Summaries and translation:** Generate or refine an article summary. Replace selected text with a translation, insert it below the original, or keep it as a quotation.
- **Article Q&A and assistants:** Ask about the current document, clarify terms, extract steps, and insert an answer at the writing position. Configure models and assistants for different tasks.

AI features require a configured model service; starting the Docker stack does not configure a model for you.

Model settings include API endpoints, model IDs, credentials, and connectivity tests, primarily for OpenAI-compatible APIs and DeepSeek. Assistants combine models, prompts, knowledge sources, and context strategies. Chat supports streaming output, stopping generation, and regenerating responses.

![AI completion demo](docs/public/assets/captures/editor/AI_auto_completion.gif)

## RAG retrieval and MCP integration

- **Full-text, vector, and hybrid search:** Find articles through Elasticsearch keyword and vector retrieval. Saved article text is indexed asynchronously.
- **RAG knowledge Q&A:** Knowledge assistants retrieve article passages as context for answers across documents. Single-article Q&A works directly with the current article's content.
- **MCP extension points:** The backend includes MCP Client / Server support and examples for tools, resources, prompts, and argument completion. These are developer integration foundations, not a complete article-management API.

Keyword search matches terms and phrases, while vector search accepts natural-language descriptions. Results show relevant passages and highlighted matches, subject to article and folder permissions. Indexing primarily covers article text rather than automatic image, audio, or arbitrary attachment parsing.

![Article search results and highlighted matches](docs/public/assets/captures/article-manage/vector-search.png)

Explore the [project introduction](docs/public/118-ARTE简介.md) and [full feature guide](docs/public/119-ARTE全部特性.md) (Chinese).

## Users and roles

Manage users, roles, menus, and operation permissions. Application features and document access are configured separately, allowing administration, reading, commenting, and editing rights to follow team responsibilities.

## Docker quick start

The standard deployment pulls images from GHCR and starts the frontend, backend, MySQL, Redis, Elasticsearch, and Draw.io. Java and Node.js are not required on the host.

Install Docker Desktop, or Docker Engine with the Compose plugin.

Download the project from the [GitHub repository](https://github.com/CYLJ126/ai-rich-text-editor) using **Code → Download ZIP** and extract it, or clone it with Git:

```sh
git clone https://github.com/CYLJ126/ai-rich-text-editor.git
cd ai-rich-text-editor
```

Open a terminal in the project root (the extracted folder if using ZIP), then run the commands for your system:

```powershell
# Windows PowerShell
cd deploy
.\compose-up.ps1
```

```sh
# Linux / macOS
cd deploy
./compose-up.sh
```

The script returns after the core services become healthy. Open
[http://localhost:8000](http://localhost:8000) and sign in with the initial
administrator account:

- Username: `admin`
- Password: `Aa111111`

Change the administrator password immediately after the first login. Before
exposing the service to a network, also replace the service passwords copied to
`deploy/.env`. For HTTPS, local-source builds, external MySQL/Elasticsearch and
operations, see the [detailed Docker deployment guide](deploy/DOCKER_DEPLOY.md).

Source builds, existing MySQL/Elasticsearch services, user-provided HTTPS certificates, and optional Kibana are also supported. See the deployment guide for configuration and arguments.

## License

Unless a file or directory says otherwise, the portions of this repository for
which the ARTE copyright holders have the right to grant a license are available
under the [MIT License](LICENSE), and the third-party libraries, models, materials 
and deployment components are applicable to their respective licenses.

The MIT License does **not** replace or override the licenses, copyright notices,
or attribution requirements of third-party code, libraries, assets, models, or
services. In particular, some backend source files retain Apache-2.0 notices from
their upstream project, and runtime dependencies remain under their respective licenses. Portions of the frontend Tiptap
editor are adapted from the MIT-licensed
[tiptap-block-editor](https://github.com/phyohtetarkar/tiptap-block-editor), with its original copyright and license
notice preserved. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LICENSES/](LICENSES/).

Before publishing a release artifact, regenerate and review a complete dependency
and license inventory for the exact resolved versions. The checked-in notice is a
human-maintained summary, not a substitute for that release-time audit.
