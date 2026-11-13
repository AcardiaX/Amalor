# 自动发布日志格式

Release 工作流读取“上一个正式 Release 的 tag → 本次提交”之间的提交，按类型分类并生成 Markdown。没有旧 Release 时读取全部非合并提交。

## 内容格式

- 简体中文区块在前，English 区块在后。
- 🌟 亮点 / Highlights：本次提交及各分类数量。
- 📝 更新内容 / What's Changed：按以下分类列出提交标题。
  - ✨ 新增功能 / New Features
  - ⚡ 优化与改进 / Improvements
  - 🔄 变更 / Changes
  - 🐛 修复 / Fixes
  - 🌐 文档与翻译 / Docs & Translations
- 贡献者从 GitHub noreply 邮箱及 Co-authored-by 信息解析。
- 完整变更对比链接放在末尾。

标题和分类标签使用中英文，条目保留原始提交文案；不会自动归纳或翻译。此流程只使用 Git 和 Python 标准库，不需要模型接口配置。

## 以后怎么写

正常提交代码，运行 Release 工作流并填写版本名即可。提交标题沿用 Conventional Commits，日志会去掉类型前缀并按类型分类：

```text
feat: add wallpaper color optimization
fix: avoid duplicate settings destinations on repeated taps
perf: reuse rasterized Pixel network artwork
docs: update configuration instructions
```

生成结果写入 `release-notes.md`、Actions 运行摘要和 Release 草稿。草稿中可继续编辑详细说明。

## 本地预览

```powershell
python .github/scripts/generate_release_notes.py --base v1.2.0 --tag v1.3.0
```

`--tag` 只用于日志链接，不会创建 tag 或 Release。
