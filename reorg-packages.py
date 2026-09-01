#!/usr/bin/env python3
"""Package reorg: technical packages -> domain modules (auth/crawler/vision).

Moves files (git-friendly plain mv), rewrites package declarations and all
import statements across main+test sources of both modules. Mapping below is
the single source of truth; run once from repo root.
"""
import os, shutil

ROOT = 'stock-calculator-common/src'
MAIN = 'stock-calculator-main/src'
PKG = 'com/zzh/stock_calculator'

# old fqn suffix -> new fqn suffix (relative to com.zzh.stock_calculator.)
MOVES = {
    # ---- auth domain ----
    'entity/UserEntity': 'auth/entity/UserEntity',
    'entity/UserProfileEntity': 'auth/entity/UserProfileEntity',
    'entity/AuthSessionEntity': 'auth/entity/AuthSessionEntity',
    'entity/OtpCodeEntity': 'auth/entity/OtpCodeEntity',
    'repository/UserRepository': 'auth/repository/UserRepository',
    'repository/UserProfileRepository': 'auth/repository/UserProfileRepository',
    'repository/AuthSessionRepository': 'auth/repository/AuthSessionRepository',
    'repository/OtpCodeRepository': 'auth/repository/OtpCodeRepository',
    'service/AuthService': 'auth/service/AuthService',
    'service/OtpService': 'auth/service/OtpService',
    'service/SessionService': 'auth/service/SessionService',
    'service/MailService': 'auth/service/MailService',
    'service/ProfileService': 'auth/service/ProfileService',
    'service/RateLimitService': 'auth/service/RateLimitService',
    'config/AuthProperties': 'auth/config/AuthProperties',
    'config/AuthInterceptor': 'auth/config/AuthInterceptor',
    'config/WebConfig': 'auth/config/WebConfig',
    'controller/AuthController': 'auth/controller/AuthController',
    'util/AuthCryptoUtil': 'auth/util/AuthCryptoUtil',
    # ---- crawler domain (CLS entities: dto -> entity, they are @Entity) ----
    'dto/ClsArticle': 'crawler/entity/ClsArticle',
    'dto/ClsArticleStock': 'crawler/entity/ClsArticleStock',
    'dto/ClsArticleSubject': 'crawler/entity/ClsArticleSubject',
    'dto/ClsSubject': 'crawler/entity/ClsSubject',
    'dto/Stock': 'crawler/entity/Stock',
    'repository/ClsArticleRepository': 'crawler/repository/ClsArticleRepository',
    'repository/ClsArticleStockRepository': 'crawler/repository/ClsArticleStockRepository',
    'repository/ClsArticleSubjectRepository': 'crawler/repository/ClsArticleSubjectRepository',
    'repository/ClsSubjectRepository': 'crawler/repository/ClsSubjectRepository',
    'repository/StockRepository': 'crawler/repository/StockRepository',
    'service/ClsArticleService': 'crawler/service/ClsArticleService',
    'service/ClsSubjectService': 'crawler/service/ClsSubjectService',
    'service/StockService': 'crawler/service/StockService',
    'service/TaskService': 'crawler/service/TaskService',
    'service/CommonHttpService': 'crawler/service/CommonHttpService',
    'task/HistoryClsDayTask': 'crawler/task/HistoryClsDayTask',
    'task/ClsSearchTask': 'crawler/task/ClsSearchTask',
    'task/ClsDayTask': 'crawler/task/ClsDayTask',
    'event/ApplicationEvent': 'crawler/event/ApplicationEvent',
    'controller/SynclsHistorycontroller': 'crawler/controller/SynclsHistorycontroller',
    'util/ParseDataUtil': 'crawler/util/ParseDataUtil',
    'util/ClsSignUtil': 'crawler/util/ClsSignUtil',
    'util/ClsDayTaskHelp': 'crawler/util/ClsDayTaskHelp',
    # ---- vision domain (import/OCR/trade-draft) ----
    'service/TradeVisionService': 'vision/service/TradeVisionService',
    'service/ImagePreprocessService': 'vision/service/ImagePreprocessService',
    'service/OcrExecutor': 'vision/OcrExecutor',  # module API (base package)
    'service/impl/GeminiTradeVisionServiceImpl': 'vision/service/impl/GeminiTradeVisionServiceImpl',
    'controller/ImportController': 'vision/controller/ImportController',
    'dto/ImageProcessOptions': 'vision/dto/ImageProcessOptions',
    'dto/TradeDraftItem': 'vision/dto/TradeDraftItem',
    'util/ImageHeaderUtil': 'vision/util/ImageHeaderUtil',
    'enums/TradeDirection': 'vision/enums/TradeDirection',
    'enums/TradeStatus': 'vision/enums/TradeStatus',
}

JAVA_DIRS = [
    f'{ROOT}/main/java/{PKG}', f'{ROOT}/test/java/{PKG}',
    f'{MAIN}/main/java/{PKG}', f'{MAIN}/test/java/{PKG}',
]

def walk(d):
    for dp, _, fns in os.walk(d):
        for fn in fns:
            if fn.endswith('.java'):
                yield os.path.join(dp, fn)

def to_java_pkg(rel):
    return rel.replace('/', '.')

# 1) move files
for base in (ROOT, MAIN):
    for old_rel, new_rel in MOVES.items():
        for sub in ('main/java', 'test/java'):
            src = os.path.join(base, sub, PKG, old_rel + '.java')
            if os.path.exists(src):
                dst = os.path.join(base, sub, PKG, new_rel + '.java')
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.move(src, dst)
                print('moved', src, '->', dst)

# 2) rewrite package + imports in every java file
# longest-first so dto/auth/X wins over dto/X patterns
repls = sorted(((to_java_pkg(k) + '.java'[:-5], to_java_pkg(v)) for k, v in MOVES.items()),
               key=lambda kv: -len(kv[0]))
all_java = [f for d in JAVA_DIRS for f in walk(d)]
changed = 0
for f in all_java:
    with open(f, encoding='utf-8') as fh:
        text = fh.read()
    orig = text
    for old, new in repls:
        text = text.replace('com.zzh.stock_calculator.' + old, 'com.zzh.stock_calculator.' + new)
    if text != orig:
        with open(f, 'w', encoding='utf-8') as fh:
            fh.write(text)
        changed += 1
print('rewrote imports/package in', changed, 'files')

# 3) move test files that mirror moved classes (same names, test tree)
TEST_MOVES = {
    'service/AuthServiceTest': 'auth/service/AuthServiceTest',
    'service/OtpServiceTest': 'auth/service/OtpServiceTest',
    'service/SessionServiceTest': 'auth/service/SessionServiceTest',
    'service/ProfileServiceTest': 'auth/service/ProfileServiceTest',
    'util/ClsSignTest': 'crawler/util/ClsSignTest',
}
for old_rel, new_rel in TEST_MOVES.items():
    src = os.path.join(ROOT, 'test/java', PKG, old_rel + '.java')
    if os.path.exists(src):
        dst = os.path.join(ROOT, 'test/java', PKG, new_rel + '.java')
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(src, dst)
        print('moved test', src, '->', dst)
