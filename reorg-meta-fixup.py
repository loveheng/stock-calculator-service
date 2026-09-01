#!/usr/bin/env python3
"""Rewrite stale package FQNs in agent-config/reachability-metadata.json after
the domain reorg. Pure text mapping (longest-first), JSON stays valid.
Run once from repo root.
"""
import json, re

META = 'stock-calculator-main/agent-config/reachability-metadata.json'
BASE = 'com.zzh.stock_calculator.'

# same mapping as reorg-packages.py MOVES + the two extra moves
MOVES = {
    'entity.UserEntity': 'auth.entity.UserEntity',
    'entity.UserProfileEntity': 'auth.entity.UserProfileEntity',
    'entity.AuthSessionEntity': 'auth.entity.AuthSessionEntity',
    'entity.OtpCodeEntity': 'auth.entity.OtpCodeEntity',
    'repository.UserRepository': 'auth.repository.UserRepository',
    'repository.UserProfileRepository': 'auth.repository.UserProfileRepository',
    'repository.AuthSessionRepository': 'auth.repository.AuthSessionRepository',
    'repository.OtpCodeRepository': 'auth.repository.OtpCodeRepository',
    'service.AuthService': 'auth.service.AuthService',
    'service.OtpService': 'auth.service.OtpService',
    'service.SessionService': 'auth.service.SessionService',
    'service.MailService': 'auth.service.MailService',
    'service.ProfileService': 'auth.service.ProfileService',
    'service.RateLimitService': 'auth.service.RateLimitService',
    'config.AuthProperties': 'auth.config.AuthProperties',
    'config.AuthInterceptor': 'auth.config.AuthInterceptor',
    'config.WebConfig': 'auth.config.WebConfig',
    'controller.AuthController': 'auth.controller.AuthController',
    'util.AuthCryptoUtil': 'auth.util.AuthCryptoUtil',
    'dto.ClsArticle': 'crawler.entity.ClsArticle',
    'dto.ClsArticleStock': 'crawler.entity.ClsArticleStock',
    'dto.ClsArticleSubject': 'crawler.entity.ClsArticleSubject',
    'dto.ClsSubject': 'crawler.entity.ClsSubject',
    'dto.Stock': 'crawler.entity.Stock',
    'repository.ClsArticleRepository': 'crawler.repository.ClsArticleRepository',
    'repository.ClsArticleStockRepository': 'crawler.repository.ClsArticleStockRepository',
    'repository.ClsArticleSubjectRepository': 'crawler.repository.ClsArticleSubjectRepository',
    'repository.ClsSubjectRepository': 'crawler.repository.ClsSubjectRepository',
    'repository.StockRepository': 'crawler.repository.StockRepository',
    'service.ClsArticleService': 'crawler.service.ClsArticleService',
    'service.ClsSubjectService': 'crawler.service.ClsSubjectService',
    'service.StockService': 'crawler.service.StockService',
    'service.TaskService': 'crawler.service.TaskService',
    'service.CommonHttpService': 'crawler.service.CommonHttpService',
    'task.HistoryClsDayTask': 'crawler.task.HistoryClsDayTask',
    'task.ClsSearchTask': 'crawler.task.ClsSearchTask',
    'task.ClsDayTask': 'crawler.task.ClsDayTask',
    'event.ApplicationEvent': 'crawler.event.ApplicationEvent',
    'controller.SynclsHistorycontroller': 'crawler.controller.SynclsHistorycontroller',
    'util.ParseDataUtil': 'crawler.util.ParseDataUtil',
    'util.ClsSignUtil': 'crawler.util.ClsSignUtil',
    'util.ClsDayTaskHelp': 'crawler.util.ClsDayTaskHelp',
    'service.TradeVisionService': 'vision.service.TradeVisionService',
    'service.ImagePreprocessService': 'vision.service.ImagePreprocessService',
    'service.OcrExecutor': 'vision.OcrExecutor',
    'service.impl.GeminiTradeVisionServiceImpl': 'vision.service.impl.GeminiTradeVisionServiceImpl',
    'service.impl.GeminiOcrExecutorImpl': 'vision.impl.GeminiOcrExecutorImpl',
    'controller.ImportController': 'vision.controller.ImportController',
    'dto.ImageProcessOptions': 'vision.dto.ImageProcessOptions',
    'dto.TradeDraftItem': 'vision.dto.TradeDraftItem',
    'util.ImageHeaderUtil': 'vision.util.ImageHeaderUtil',
    'enums.TradeDirection': 'vision.enums.TradeDirection',
    'enums.TradeStatus': 'vision.enums.TradeStatus',
}

with open(META, encoding='utf-8') as fh:
    text = fh.read()

before = text.count('com.zzh.stock_calculator')
# dto.auth subpackage prefix first, then class-level FQNs longest-first
text = text.replace(BASE + 'dto.auth.', BASE + 'auth.dto.')
for old in sorted(MOVES, key=len, reverse=True):
    text = text.replace(BASE + old, BASE + MOVES[old])
after = text.count('com.zzh.stock_calculator')
# drop synthesized package-info entries for packages that no longer exist
for pkg in ('dto', 'entity'):
    blk = '    {\n      "type": "com.zzh.stock_calculator.%s.package-info"\n    },\n' % pkg
    if blk in text:
        text = text.replace(blk, '')
        print('removed', pkg, 'package-info entry')
with open(META, 'w', encoding='utf-8') as fh:
    fh.write(text)
print('FQN occurrences: before=%d after=%d' % (before, after))
json.load(open(META, encoding='utf-8'))  # validate JSON still parses
print('JSON valid')

# report any remaining old-package refs that should have moved
stale = sorted(set(re.findall(
    r'com\.zzh\.stock_calculator\.(?:entity|repository|service|dto|task|event|enums)\.[\w.$]+',
    text)))
stale = [s for s in stale if not re.match(
    r'com\.zzh\.stock_calculator\.(?:auth|crawler|vision)\.', s)]
for s in stale:
    print('STALE:', s)
if not stale:
    print('no stale package refs')
