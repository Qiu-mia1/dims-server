-- 智能功能状态灯扩展冗余列（《智能功能状态灯 · 信号源说明》）
--
-- slice_stats 原有 noa_active / aeb_active / apa_active / acc_active / lcc_active
-- 五列不足以表达文档定义的全部灯位，这里补齐：
--   roaming_active  NOA 漫游（debug.sm 模式码 3）
--   avp_active      记忆泊车 AVP / 远程泊车 RADS（模式码 8 / 9）
--   fcw_active      FCW（融合状态机 state==1 或 momenta_public adas_status=='FCW'）
--   aes_active      AES（/mff/aeb_sts_info aes_state==3）
--   ess_active      ESS（/mff/aeb_sts_info ess_state==3）
-- 语义沿用既有约定：切片内「任一帧激活过」即为 TRUE，NULL 表示统计未产出。
ALTER TABLE slice_stats
    ADD COLUMN roaming_active BOOLEAN NULL DEFAULT NULL COMMENT 'NOA 漫游是否有激活段（模式码 3）' AFTER lcc_active,
    ADD COLUMN avp_active     BOOLEAN NULL DEFAULT NULL COMMENT 'AVP/RADS 是否有激活段（模式码 8/9）' AFTER roaming_active,
    ADD COLUMN fcw_active     BOOLEAN NULL DEFAULT NULL COMMENT 'FCW 是否有触发' AFTER avp_active,
    ADD COLUMN aes_active     BOOLEAN NULL DEFAULT NULL COMMENT 'AES 是否有触发' AFTER fcw_active,
    ADD COLUMN ess_active     BOOLEAN NULL DEFAULT NULL COMMENT 'ESS 是否有触发' AFTER aes_active;
