"""Disposable local M3 browser check; reads synthetic credentials from env."""
import json
import os
import time
import urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright

base = os.environ.get("QA_UI_BASE", "http://localhost:5174")
mailbox = os.environ.get("QA_SMS_BASE", "http://127.0.0.1:18083") + "/messages?destination=0900000011"
shots = Path(__file__).resolve().parent / "screenshots"
shots.mkdir(parents=True, exist_ok=True)

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(channel="chrome", headless=True)
    context = browser.new_context(viewport={"width": 1280, "height": 800})
    page = context.new_page()
    problems = []
    page.on("pageerror", lambda error: problems.append(str(error)))
    page.goto(base, wait_until="domcontentloaded")
    page.get_by_label("Tên đăng nhập").fill("qa-sender")
    page.get_by_label("Mật khẩu").fill(os.environ["APP_QA_PASSWORD"])
    page.get_by_role("button", name="Đăng nhập", exact=True).click()
    page.get_by_label("Mã OTP").wait_for(timeout=15000)
    otp = None
    for _ in range(20):
        request = urllib.request.Request(mailbox, headers={"X-QA-SMS-Token": os.environ["QA_SMS_TOKEN"]})
        with urllib.request.urlopen(request, timeout=10) as response:
            messages = json.load(response)
        otp = next((message["otp"] for message in messages if message["type"] == "OTP"), None)
        if otp:
            break
        time.sleep(0.5)
    assert otp, "OTP not delivered"
    page.get_by_label("Mã OTP").fill(otp)
    page.get_by_role("button", name="Xác minh OTP").click()
    page.get_by_text("Tổng quan", exact=True).wait_for(timeout=15000)
    page.get_by_role("button", name="Lịch sử giao dịch").click()
    page.get_by_text("Giao dịch của tôi").wait_for(timeout=15000)
    assert page.url.startswith(base + "/customer/history")
    history = context.request.get(base + "/api/v1/transactions?page=0&size=20")
    assert history.status == 200, history.status
    items = history.json()["items"]
    assert items, "QA transfer seed/smoke did not create history"
    page.screenshot(path=str(shots / "m3-history.png"), full_page=True)
    page.goto(base + "/customer/history/" + items[0]["reference"])
    page.get_by_text("Thông tin giao dịch").wait_for(timeout=15000)
    page.screenshot(path=str(shots / "m3-detail.png"), full_page=True)
    page.get_by_role("button", name="Hạn mức").click()
    page.get_by_text("Hạn mức hiệu lực").wait_for(timeout=15000)
    page.screenshot(path=str(shots / "m3-limits.png"), full_page=True)
    page.reload()
    page.get_by_text("Hạn mức hiệu lực").wait_for(timeout=15000)
    assert not problems, problems
    print("PASS M3 browser: login OTP, history list/detail, limits, reload; no page errors")
    context.close()
    browser.close()
