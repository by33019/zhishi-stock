-- M3-11 / P4：区分"计数未采集"与"计数确实为 0"。
--
-- 背景：job_execution_summary 的五个 count 列都是 NOT NULL DEFAULT 0。
-- 于是"这次执行没记录计数"与"这次执行处理了 0 条"在库里完全同形，
-- 而契约要求页面在未采集时写"计数未采集"、不要渲染一排 0
-- （一排 0 会被读成"任务跑了但什么都没干"，那是另一个结论）。
--
-- 为什么不在 Java 侧用"五个计数全为 0"来推断：0 是合法取值。
-- 资讯采集在"本轮来源没有新内容"时 fetched=inserted=skipped=0，这是正常的成功执行，
-- 把它显示成"计数未采集"属于让真实数据看起来像缺失。
--
-- 历史行（本表当前为空，但迁移必须对已存在的部署成立）保持 0：
-- 那些行确实没有采集标记，标成"已采集"就是编造。
ALTER TABLE `job_execution_summary`
  ADD COLUMN `counts_available` tinyint(1) NOT NULL DEFAULT 0
    COMMENT '本次执行的计数是否被采集（0=未采集，1=已采集；不能由计数本身推出）'
    AFTER `output_count`;
