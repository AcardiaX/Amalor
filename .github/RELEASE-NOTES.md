# 自动发布日志

Release 工作流自动读取“上一个正式 Release 的 tag → 本次提交”之间的提交，生成中文在前、英文在后的 Markdown。没有旧 Release 时读取全部非合并提交。

输出包括 🌟 亮点、📝 更新内容，以及以下分类：

- ✨ 新增功能 / New Features
- ⚡ 优化与改进 / Improvements
- 🔄 变更 / Changes
- 🐛 修复 / Fixes
- 🌐 文档与翻译 / Docs & Translations

同类实现会归并为面向用户的说明，中文与英文描述相同的事实。贡献者从提交中的 GitHub noreply 邮箱及 Co-authored-by 信息解析，完整对比链接放在末尾。

## 一次配置

在仓库 **Settings → Secrets and variables → Actions** 中设置：

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Secret | `RELEASE_NOTES_API_KEY` | 模型接口密钥 |
| Variable | `RELEASE_NOTES_API_URL` | 完整的 Chat Completions 地址，默认 `https://api.openai.com/v1/chat/completions` |
| Variable | `RELEASE_NOTES_MODEL` | 模型名称，默认 `gpt-4.1-mini` |

可使用 OpenAI 或兼容网关。模型需支持 Chat Completions 与 JSON 输出。普通模型使用 `temperature` 和 `max_tokens`；GPT-5/6、o 系列推理模型使用 `max_completion_tokens` 与低推理预算。请求显式关闭流式输出，也可接收网关返回的标准 Chat Completions SSE。密钥只传给日志生成步骤，不写入文件或日志。GitHub 的 `GITHUB_TOKEN` 用于发布与依赖下载，不能代替模型接口密钥。

GitHub Models 已于 2026 年 7 月停用，因此此流程使用可配置接口，而非旧的 GitHub Models 服务。

## 以后怎么写

**不需要手写 changelog。** 正常提交代码，运行 Release 工作流并填写版本名即可。

提交标题沿用 Conventional Commits：

```text
feat: add wallpaper color optimization
fix: avoid duplicate settings destinations on repeated taps
perf: reuse rasterized Pixel network artwork
docs: update configuration instructions
```

标题说清改了什么；若影响默认值、支持范围或操作方式，在 commit body 中补充事实，例如：

```text
Use ColorSpec 2025 for the COUI/NX wallpaper palette.
Disabled by default. Restart Wallpaper & personalization after enabling it.
```

生成器读取提交标题、正文和有限长度的代码差异，合并重复事项并翻译成双语。只传入已提交的 Git 内容，未提交的本地测试、分析及构建产物不会参与生成。大范围差异会截断，重要的默认值和兼容性说明最好写入 commit body。

模型输出必须关联到本次范围内的真实提交，覆盖全部提交，并通过格式检查。没有密钥、接口不可用、输出截断或校验失败时，自动回退到带中文标题和 Emoji 的分类提交记录，明确标注未做语义归纳或翻译；不会因此阻断 Release 构建。

生成结果写入 `release-notes.md`、Actions 运行摘要和已有的 Release 草稿。

## 先验证接口

在 Release 工作流中勾选 `notes_only`，即可只生成日志，上传 Markdown 并验证真正的双语输出；APK 构建、签名和 Release 创建步骤会跳过。纯日志模式在接口或输出校验失败时明确失败，避免将回退记录当成翻译成功。

失败提示区分 `configuration`（配置）、`api_http`（HTTP 状态）、`api_response`（接口响应格式）、`model_content`（模型正文 JSON）和 `notes_validation`（条目/语言/提交关联）。日志不会打印响应正文或密钥；普通 Release 的回退文本也会注明这一原因。

## 本地预览

```powershell
python .github/scripts/generate_release_notes.py --base v1.2.0 --tag v1.3.0 --no-ai
```

去掉 `--no-ai`，并在环境变量中配置上述接口参数，即可预览模型总结。这里的 `--tag` 只用于日志链接，不会创建 tag 或 Release。
