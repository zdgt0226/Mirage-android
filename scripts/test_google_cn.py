#!/usr/bin/env python3
import json
import subprocess
import time
import urllib.request
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

DEVICE = "BH905W2A9G"
DEBUG_API = "http://127.0.0.1:9090"

# 126 google@cn 域名列表
DOMAINS = [
    "google.cn", "g.cn", "gkecnapps.cn", "googlecnapps.cn", "gstatic.cn", "gstaticcnapps.cn",
    "265.com", "gvt1-cn.com", "gvt2-cn.com", "recaptcha-cn.net", "recaptcha.net",
    "www.recaptcha-cn.net", "www.recaptcha.net", "2mdn-cn.net", "2mdn.net", "admob-cn.com",
    "adservice.google.com", "app-analytics-services.com", "app-measurement-cn.com", "app-measurement.com",
    "apps5.oingo.com", "avail.googleflights.net", "beacons.gcp.gvt2.com", "beacons.gvt2.com",
    "beacons2.gvt2.com", "beacons3.gvt2.com", "c.admob.com", "c.android.clients.google.com",
    "cache-management-prod.google.com", "cache.pack.google.com", "checkin.gstatic.com",
    "clickserve.cc-dt.com", "clickserve.dartsearch.net", "clickserver.googleads.com",
    "clientservices.googleapis.com", "cn.widevine.com", "connectivitycheck.gstatic.com",
    "csi.gstatic.com", "dartsearch-cn.net", "dg-meta.video.google.com", "dl.google.com",
    "dl.l.google.com", "doubleclick-cn.net", "doubleclick.net", "download.mlcc.google.com",
    "download.tensorflow.google.com", "fontfiles.googleapis.com", "fonts.googleapis.com",
    "fonts.gstatic.com", "g0.gstatic.com", "g1.gstatic.com", "g2.gstatic.com", "g3.gstatic.com",
    "gonglchuangl.net", "gongyichuangyi.net", "google-analytics-cn.com", "google-analytics.com",
    "googleadservices-cn.com", "googleadservices.com", "googleanalytics.com", "googleapis-cn.com",
    "googleapps-cn.com", "googleflights-cn.net", "googleoptimize-cn.com", "googleoptimize.com",
    "googlesyndication-cn.com", "googlesyndication.com", "googletagmanager-cn.com", "googletagmanager.com",
    "googletagservices-cn.com", "googletagservices.com", "googletraveladservices-cn.com",
    "googletraveladservices.com", "googlevads-cn.com", "gstatic-cn.com", "gstaticadssl.l.google.com",
    "gtm.oasisfeng.com", "imasdk.googleapis.com", "pagead-googlehosted.l.google.com",
    "performanceparameters.googleapis.com", "prod-controlbe.floonet.goog", "prod-databe.floonet.goog",
    "prod.databe.floonet.goog", "qiao-cn.com", "qpx.googleflights.net", "redirector.bdn.dev",
    "redirector.c.chat.google.com", "redirector.c.mail.google.com", "redirector.c.pack.google.com",
    "redirector.c.youtubeeducation.com", "redirector.gcpcdn.gvt1.com", "redirector.gvt1.com",
    "redirector.offline-maps.gvt1.com", "redirector.snap.gvt1.com", "redirector.xn--ngstr-lra8j.com",
    "safebrowsing-cache.google.com", "safebrowsing.googleapis.com", "service.urchin.com",
    "ssl-google-analytics.l.google.com", "ssl.gstatic.com", "staging-controlbe.floonet.goog",
    "staging-databe.floonet.goog", "staging.databe.floonet.goog", "tac.googleapis.com",
    "tools.google.com", "update.googleapis.com", "wear.googleapis.com", "www-google-analytics.l.google.com",
    "www-googletagmanager.l.google.com", "www.destinationurl.com", "www.gstatic.com", "www.pxcc.com",
    "pki-goog.l.google.com", "c.pki.goog", "i.pki.goog", "o.pki.goog", "crl.pki.goog", "crls.pki.goog",
    "ocsp.pki.goog", "crashlyticsreports-pa.googleapis.com", "firebase-settings.crashlytics.com",
    "update.crashlytics.com", "redirector.c.play.google.com", "ggpht.cn"
]

def query_debug_route(domain):
    try:
        req = urllib.request.Request(
            f"{DEBUG_API}/debug/route",
            data=json.dumps({"domain": domain, "port": 443, "proto": "tcp"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data.get("decision", "unknown"), data.get("is_strict_cn_domain", False)
    except Exception:
        return "unknown", False

def test_domain_on_device(domain):
    # 1. 路由查询
    decision, is_strict_cn = query_debug_route(domain)
    
    # 2. 在真机上执行 curl 测试
    # 使用 curl 获取 HTTP 状态码、耗时和 DNS 耗时
    cmd = [
        "adb", "-s", DEVICE, "shell",
        f"curl -s -m 5 -o /dev/null -w '%{{http_code}}|%{{time_namelookup}}|%{{time_connect}}|%{{time_total}}' -I https://{domain} 2>/dev/null || "
        f"curl -s -m 5 -o /dev/null -w '%{{http_code}}|%{{time_namelookup}}|%{{time_connect}}|%{{time_total}}' -I http://{domain} 2>/dev/null || "
        "echo 'FAIL|0|0|0'"
    ]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
        output = p.stdout.strip().split("\n")[-1]
        parts = output.split("|")
        if len(parts) >= 4 and parts[0] != "FAIL" and parts[0] != "000":
            http_code = parts[0]
            dns_time = float(parts[1]) * 1000 # ms
            conn_time = float(parts[2]) * 1000 # ms
            total_time = float(parts[3]) * 1000 # ms
            success = True
        else:
            http_code = parts[0] if parts else "ERR"
            dns_time, conn_time, total_time = 0, 0, 0
            success = False
    except Exception as e:
        http_code = "TIMEOUT"
        dns_time, conn_time, total_time = 0, 0, 0
        success = False

    return {
        "domain": domain,
        "rule_decision": decision,
        "is_strict_cn": is_strict_cn,
        "success": success,
        "http_code": http_code,
        "dns_time_ms": round(dns_time, 2),
        "conn_time_ms": round(conn_time, 2),
        "total_time_ms": round(total_time, 2),
    }

def main():
    print(f"[*] 开始对 {len(DOMAINS)} 个 google@cn 域名在索尼设备 ({DEVICE}) 上进行批量连接与路由测试...")
    results = []
    
    # 限制并发为 8，避免压垮单机 smoltcp socket 队列
    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = {executor.submit(test_domain_on_device, d): d for d in DOMAINS}
        count = 0
        for f in as_completed(futures):
            res = f.result()
            results.append(res)
            count += 1
            status_icon = "✓" if res["success"] else "✗"
            print(f"[{count:03d}/{len(DOMAINS)}] {status_icon} {res['domain']:<35} -> HTTP {res['http_code']:<4} | 路由: {res['rule_decision']:<6} | 耗时: {res['total_time_ms']}ms")

    # 统计分类
    total = len(results)
    success_count = sum(1 for r in results if r["success"])
    fail_count = total - success_count
    
    proxy_routes = sum(1 for r in results if r["rule_decision"] == "proxy")
    direct_routes = sum(1 for r in results if r["rule_decision"] == "direct")

    # HTTP 状态码分布
    status_dist = {}
    for r in results:
        code = r["http_code"]
        status_dist[code] = status_dist.get(code, 0) + 1

    summary = {
        "total_domains": total,
        "successful_connections": success_count,
        "failed_connections": fail_count,
        "success_rate_percent": round(success_count / total * 100, 2),
        "route_distribution": {
            "proxy": proxy_routes,
            "direct": direct_routes
        },
        "http_status_distribution": status_dist,
        "details": sorted(results, key=lambda x: (not x["success"], x["domain"]))
    }

    with open("/tmp/google_cn_test_report.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n" + "="*60)
    print(f"[*] 批量测试已完成！")
    print(f"    - 总测试域名: {total}")
    print(f"    - 成功连通数: {success_count} ({summary['success_rate_percent']}%)")
    print(f"    - 连通失败数: {fail_count}")
    print(f"    - 路由分流: 走代理 {proxy_routes} 个 | 走直连 {direct_routes} 个")
    print(f"    - 结果已保存至: /tmp/google_cn_test_report.json")
    print("="*60)

if __name__ == "__main__":
    main()
