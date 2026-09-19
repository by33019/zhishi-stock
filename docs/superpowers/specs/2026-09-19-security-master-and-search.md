# M2-04 证券主数据与搜索建议（STK-01 / STK-02）

> 任务编号：M2-04（路线图见 `docs/superpowers/plans/2026-09-19-mvp-delivery-roadmap.md`）
> 依赖：M2-01 ✅（交易日历）
> 契约来源：`docs/RESTful-API.md` 第 9 章 STK-01 / STK-02、§4.1 `SecuritySummary`、§3.5 分页

---

## 1. 背景

市场总览（MKT-01~04）已能回答"今天大盘怎么样"。但用户的下一个动作一定是"找一只具体的股票"——
无论是点搜索框、还是从列表里筛。当前后端**没有任何证券主数据接口**：

- `stock_security` 表结构（V3）已就绪，但**库内无数据、无代码引用**；
- 模拟证券全集只存在于 `SimulatedSecurityQuoteProvider` 内部，仅供广度计数使用，未对外暴露；
- 前端 `GlobalSearch.vue` 仍是写死的跳转（`router.push('/stocks/19876543210001')`）。

## 2. 目标

交付两个 PUBLIC 接口：

| 编号 | 方法与路径 | 用途 |
| --- | --- | --- |
| STK-01 | `GET /api/v1/securities/search` | 搜索建议（输入联想），带 `matchedField` 与 `highlight` |
| STK-02 | `GET /api/v1/securities` | 证券主数据列表（分页 + 筛选 + 排序） |

**验收标准（路线图原文）**：六位代码 + 交易所唯一；搜索 P95 < 500ms；单测 + 契约测试。

## 3. 不在范围

- **STK-03**（`/securities/{securityId}` 详情）留到 M2-05——个股快照本身就会带出 `SecuritySummary`，
  且详情里的 `primarySector` 依赖板块数据（M2-07）。
- **不落库**。`stock_security` 表继续空置，证券主数据入库随真实数据源接入一并处理。
- **不做拼音数据**（见 §4.2）。
- 不做 `sectorId` 的板块关系（板块数据归 M2-07）。
- 前端页面接入归 M2-08 / M2-09。

## 4. 数据来源

### 4.1 落地方式：纯模拟 Provider（内存）

沿用 M2-01 / M2-03 的既定做法：`stock-market/domain` 定义端口，`stock-integration` 放确定性模拟实现。

新增端口：

```java
@FunctionalInterface
public interface SecurityMasterProvider {
    /** 返回指定市场的全部证券主数据；市场不受支持时返回空列表。 */
    List<SecuritySummary> findAll(String marketCode);
}
```

模拟实现 `SimulatedSecurityMasterProvider` **不重复定义证券全集**，而是从既有的
`SecurityQuoteProvider.fetchUniverse(marketCode, tradeDate)` 投影出 `SecuritySummary`：

| `SecuritySummary` 字段 | 取值来源 |
| --- | --- |
| `securityId` | `SecurityQuote.securityId`（`sim-<code>`） |
| `securityCode` | `SecurityQuote.securityCode` |
| `securityName` | `SecurityQuote.securityName`（`模拟证券<code>`） |
| `exchangeCode` / `boardCode` / `securityType` | 同名映射 |
| `fullSymbol` | `exchangeCode + "." + securityCode`（如 `SH.600000`） |
| `listingStatus` | `suspended ? "SUSPENDED" : "LISTED"` |
| `isSt` / `isSuspended` | `SecurityQuote.st()` / `SecurityQuote.suspended()` |
| `priceScale` | 固定 `2` |

`tradeDate` 取**最近一个交易日**（`TradingCalendarProvider.find(CN, today)`：当日是交易日就用当日，
否则用其 `previousTradeDate`）。模拟环境里停牌状态取自最近交易日行情；
真实环境下该字段由主数据源直接提供，替换适配器即可，应用层不改动。

**为什么不复制一份 `BLOCKS`**：代码段一旦在两处各写一遍，改动其中一处就会让"主数据"与"广度计数"
指向不同的证券全集，且**没有任何测试会红**。投影的做法天然只有一份真相。

### 4.2 拼音：保留能力，不填值

`SecuritySummary` **包含** `pinyin` 与 `pinyinAbbr` 两个组件，但都标 `@JsonIgnore`，
因此 **JSON 输出严格等于 API 文档 §4.1 的 11 个字段**。这样做而不是把它们拆到另一个类型里：
匹配逻辑需要读到它们，而多一层 `SecurityRecord` 包装只为一个当前恒为 `null` 的字段服务，
不划算。`MarketOverview.BreadthData.totalCount()` 已有同样的先例。

搜索匹配保留了 `PINYIN` / `PINYIN_ABBR` 两个 `matchedField` 取值与完整匹配分支，
只是模拟数据里拼音为 `null`，因此本期实际只按代码与名称匹配；
单测用一只**带拼音的桩数据**（贵州茅台 / `guizhoumaotai` / `gzmt`）覆盖这两条分支，
能力不会因为"当前没数据"而腐烂。

理由与 M2-02 的"新股首日不限幅"同源：模拟名称是 `模拟证券600000`，
对它生成拼音**没有任何可核实的含义**。与其编一份假拼音污染真实逻辑，不如留空。

## 5. 接口契约

### 5.1 STK-01 `GET /api/v1/securities/search`

| 参数 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- |
| `q` | 是 | — | 去空格后 1–50 字符 |
| `types` | 否 | — | 逗号分隔的 `securityType` |
| `exchangeCodes` | 否 | — | 逗号分隔的 `exchangeCode` |
| `limit` | 否 | 10 | 1–20 |

响应 `data`：

```json
{
  "items": [
    {
      "security": {
        "securityId": "sim-600000",
        "fullSymbol": "SH.600000",
        "securityCode": "600000",
        "securityName": "模拟证券600000",
        "exchangeCode": "SH",
        "securityType": "STOCK",
        "boardCode": "MAIN",
        "listingStatus": "LISTED",
        "isSt": false,
        "isSuspended": false,
        "priceScale": 2
      },
      "matchedField": "CODE",
      "highlight": "600"
    }
  ]
}
```

**`items` 的层级**：API 文档写作"`items`：`SecuritySummary`、`matchedField`、`highlight`"，
既可读作扁平也可读作嵌套。本实现采用**嵌套 `security`**，与 §4.2 `QuoteSnapshot.security` 保持一致；
若后续确认为扁平，只需改 `SecuritySearchMatch` 的 Jackson 注解，匹配逻辑不动。

**`highlight`** 是**命中字段中的实际命中子串原文**（保留原始大小写），不做 HTML 标记——
标记交给前端，避免把转义问题引进后端。未命中时（理论上不会发生，因为只有命中才入选）为 `null`。

### 5.2 STK-02 `GET /api/v1/securities`

| 参数 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `keyword` | 否 | — | 对 `securityCode` / `securityName` / `fullSymbol` 做**包含**匹配（大小写不敏感） |
| `securityType` | 否 | — | 精确匹配（大小写不敏感） |
| `exchangeCode` | 否 | — | 精确匹配（大小写不敏感） |
| `boardCode` | 否 | — | 精确匹配（大小写不敏感） |
| `listingStatus` | 否 | — | 精确匹配（大小写不敏感） |
| `sectorId` | 否 | — | 见 §11 |
| `page` | 否 | 1 | ≥ 1 |
| `size` | 否 | 20 | 1–100 |
| `sort` | 否 | `fullSymbol,asc` | `field,asc` / `field,desc`，字段见 §7 |

响应 `data` 为 `PageData<SecuritySummary>`：

```json
{ "items": [], "page": 1, "size": 20, "total": 0, "totalPages": 0, "hasNext": false }
```

`PageData` 落在 `stock-common`（与 `ApiResponse` 同级）：它是跨域共用的响应外壳，
后续榜单（QTE-01）、资讯（NEWS-01）等分页接口都要用，放进行情域会让别的域反向依赖它。

`totalPages` 用 `(total + size - 1) / size` 计算（向上取整）；`total = 0` 时 `totalPages = 0`。

## 6. 搜索匹配规则

对 `q` 去空格得到 `needle`，按**固定优先级**取第一个命中的字段（一只证券只产生一条结果）：

| 优先级 | `matchedField` | 命中条件 |
| --- | --- | --- |
| 1 | `CODE` | `securityCode` 以 `needle` 开头，或 `fullSymbol` 以 `needle` 开头（大小写不敏感） |
| 2 | `NAME` | `securityName` **包含** `needle` |
| 3 | `PINYIN` | `pinyin` 以 `needle` 开头（本期恒为 `null`，不命中） |
| 4 | `PINYIN_ABBR` | `pinyinAbbr` 以 `needle` 开头（本期恒为 `null`，不命中） |

**为什么代码用前缀、名称用包含**：代码是结构化标识，用户输入 `600` 期望的是 `600xxx` 这一段；
名称是自然语言，用户输入"银行"期望匹配到名字里任何位置的"银行"。

**排序**：先按 `matchedField` 优先级升序，再按 `fullSymbol` 升序。
第二个键是**确定性兜底**——没有它，同优先级内的顺序取决于底层集合遍历顺序，
同一批数据可能给出不同结果（与 M2-02 的 `ruleCode` 字典序兜底同一考虑）。

**截断**：排序后取前 `limit` 条。

## 7. 列表筛选与排序

**筛选**：所有条件同时成立（AND）。`keyword` 之外的字段做精确匹配。

**排序白名单**（`sort` 的 `field` 部分）：

| 字段 | 说明 |
| --- | --- |
| `fullSymbol` | 默认，如 `SH.600000` |
| `securityCode` | 交易所内代码 |
| `securityName` | 证券名称 |
| `securityId` | 主键 |

不在白名单内 → **400**，不静默回落到默认排序。
理由：排序被静默忽略时，调用方拿到的是"顺序不对但看起来正常"的响应，极难排查；
而筛选值不匹配最多是空结果，语义上仍然诚实。这是两类参数**刻意区别对待**的原因。

排序方向只接受 `asc` / `desc`（大小写不敏感）；`sort` 缺少方向时按 `asc`。
比较用 `String.compareTo`（`fullSymbol` / `securityCode` / `securityId` 均为 ASCII，顺序稳定）；
`securityName` 为中文，按 UTF-16 码点序，仅保证**确定性**，不承诺拼音序。

## 8. 参数校验

集中在用例层（与 M2-03 一致），非法值抛 `InvalidSecurityQueryException` → **400 `INVALID_REQUEST`**：

| 场景 | 处理 |
| --- | --- |
| `q` 缺失 / 去空格后为空 / 超过 50 字符 | 400 |
| `limit` 不在 1–20 | 400 |
| `page` < 1 | 400 |
| `size` 不在 1–100 | 400 |
| `sort` 字段不在白名单 / 方向不是 `asc`\|`desc` | 400 |
| `types` / `exchangeCodes` 含空项 | 忽略空项 |
| `types` / `exchangeCodes` / 各筛选值**取值不存在** | 不报错，返回空结果 |
| `marketCode` 不受支持 | 不涉及（接口无此参数，内部固定 `CN`） |

**为什么筛选值不校验合法性**：`types=ETF` 是完全合法的取值，只是当前模拟数据里没有 ETF——
报 400 会把"没有数据"错报成"参数非法"。不匹配即空才是诚实语义。

## 9. 改动清单

**新增**

| 文件 | 说明 |
| --- | --- |
| `stock-common/.../api/PageData.java` | 通用分页响应外壳 |
| `stock-market/.../domain/SecuritySummary.java` | 证券摘要（API §4.1） |
| `stock-market/.../domain/SecurityMasterProvider.java` | 主数据端口 |
| `stock-market/.../domain/SecuritySearchMatch.java` | 搜索命中项 + 嵌套 `MatchedField` 枚举 |
| `stock-market/.../domain/SecuritySearchResult.java` | 搜索结果 |
| `stock-market/.../application/SecurityListCriteria.java` | 列表查询条件 |
| `stock-market/.../application/SecurityQueryService.java` | 搜索与列表用例 |
| `stock-market/.../application/InvalidSecurityQueryException.java` | → 400 |
| `stock-integration/.../market/SimulatedSecurityMasterProvider.java` | 模拟主数据（投影自行情全集） |
| `stock-backend/.../web/SecurityController.java` | STK-01 / STK-02 |

**修改**

| 文件 | 改动 |
| --- | --- |
| `stock-backend/.../security/SecurityConfiguration.java` | `GET /api/v1/securities` 与 `/api/v1/securities/**` 放行 |
| `stock-backend/.../web/GlobalExceptionHandler.java` | +`InvalidSecurityQueryException` → 400 |
| `stock-backend/.../config/BackendConfiguration.java` | +`SecurityQuoteProvider`、`SecurityMasterProvider`、`SecurityQueryService` Bean |
| `frontend/src/types/domain.ts` | +`PageData` / `SecurityType` / `ListingStatus` / `SecurityMatchedField` / `SecuritySummary` / `SecuritySearchMatch` / `SecuritySearchResult` / `SecurityListQuery` |

> `SecurityQuoteProvider` 此前**没有 Spring Bean**——`SimulatedQuoteProvider` 内部自建了一份实例。
> 本次注册为 Bean 供主数据 Provider 注入；`SimulatedQuoteProvider` 不动（该实现无状态且完全确定性，
> 两份实例产出逐位相同），以保持改动最小。

## 10. 验收方式

- **单测** `SecurityQueryServiceTest`：搜索匹配优先级、大小写、`types`/`exchangeCodes` 过滤、
  `limit` 截断、排序确定性、`highlight` 取值、全部参数校验分支；列表筛选、排序白名单、分页边界。
- **单测** `SimulatedSecurityMasterProviderTest`：全集 5149 只、`fullSymbol` 唯一、
  `(exchangeCode, securityCode)` 唯一、六位代码、`listingStatus` 与停牌一致。
- **契约测试** `SecurityControllerContractTest`：两条接口的状态码、字段名、分页结构与错误码。
- **性能**：内存线性扫描 5149 条，单次远低于 1ms。加一条**宽松的性能冒烟测试**
  （预热后 100 次搜索，P95 断言 < 500ms），既落实验收标准，又不会在 CI 上抖动。

## 11. 已知取舍

1. **`sectorId` 筛选当前必然返回空页**。板块关系数据（`stock_sector` / `stock_security_sector`）
   在 M2-07 之前不存在，因此"没有任何证券属于该板块"在当下是**事实**而非错误。
   保留该参数是为了契约完整；M2-07 落地板块后此处自然生效。
2. **`securityId` 是 `sim-<code>` 字符串**，不是真实 Snowflake。项目约定"业务 ID 前端全程 string"，
   该形式满足字符串要求；真实主数据接入后由数据源提供数值型 Snowflake 字符串。
3. **不落库**：`stock_security` 表继续空置。代价是 M2-06 榜单无法用 SQL JOIN 证券主数据，
   需在应用层按 `securityId` 关联内存全集。收益是不引入 seed 脚本，且与 M2-01 / M2-03 决策一致。
4. **拼音字段无数据**，但 `PINYIN` / `PINYIN_ABBR` 匹配能力与单测已就位（见 §4.2）。
5. **`items` 采用嵌套 `security`**，API 文档表述有歧义（见 §5.1）。
6. **主数据从行情全集投影**：依赖"模拟停牌状态与日期无关"这一实现特征。该特征由
   `SimulatedSecurityQuoteProvider.targetState(ordinal)` 只依赖序号保证；
   `SimulatedSecurityMasterProviderTest` 不对此加断言，若将来目标状态改为依赖日期，需重新评估缓存/投影策略。
