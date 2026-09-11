import io
import os
import sys

TOK = open('_tok.txt', encoding='utf-8').read()
ST = open('_st.txt', encoding='utf-8').read()
NAV = open('_nav_simple.txt', encoding='utf-8').read()


def nav(active):
    n = NAV
    for k in ('TT', 'PP', 'EE', 'AA', 'SS'):
        n = n.replace(k, 'on' if k == active else '')
    return n


# Artboards this run would have changed but did not, because they already existed with different
# content. Reported at the end so drift is visible instead of silent.
SKIPPED = []

# `--force` rewrites them anyway. Without it, an artboard that differs from what this script would
# produce is left alone.
FORCE = '--force' in sys.argv


def page(name, body, active='TT', tabs=True, extra=''):
    # The editor looks for this exact head line and warns on every artboard without it.
    html = '<script src="./support.js"></script>' + chr(10) + TOK + extra + ST + body + (nav(active) if tabs else '')

    # Refuse to clobber an artboard that has moved on.
    #
    # This script regenerates every artboard from source, and it used to do so unconditionally — which
    # is fine while the script is ahead of the committed files and destructive when it is behind. It
    # is behind for exactly one artboard: `Plan.dc.html` on disk is a newer design (one card per
    # profile, with the events that start it nested inside) than the flat version this file still
    # generates. Running it as it was would have silently reverted that, and the loss would have
    # looked like a successful build.
    #
    # So the rule is: rewriting an artboard to something *different* is a decision, not a side effect.
    # Deleting the target or passing `--force` is how you make it.
    if os.path.exists(name) and not FORCE:
        with open(name, encoding='utf-8') as f:
            if f.read() != html:
                SKIPPED.append(name)
                return
    open(name, 'w', encoding='utf-8').write(html)


page('Main.dc.html', """
<div class="body">
  <div style="height:14px"></div>
  <h1>Nothing is blocked<br>right now.</h1>
  <div class="sub">Your next block starts in 3 hours.</div>

  <div style="height:26px"></div>

  <div class="card" style="padding:22px;display:flex;flex-direction:column;align-items:center;gap:18px">
    <div style="position:relative;width:186px;height:186px">
      <svg width="186" height="186" viewBox="0 0 186 186">
        <circle cx="93" cy="93" r="82" fill="none" stroke="#232B36" stroke-width="10"/>
      </svg>
      <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px">
        <div style="font-size:15px;color:var(--mut)">Next up</div>
        <div style="font-size:34px;font-weight:700;letter-spacing:-1px">21:00</div>
        <div style="font-size:13px;color:var(--dim)">Deep work &middot; 2h</div>
      </div>
    </div>
    <button class="btn">Start a block now</button>
  </div>

  <div style="height:20px"></div>
  <div class="lbl">Today</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:16px 18px;display:flex;align-items:center;justify-content:space-between">
    <div>
      <div style="font-size:16px;font-weight:600">4h 20m blocked</div>
      <div style="font-size:13px;color:var(--ok);margin-top:3px">2h 18m less screen time than before Curfew</div>
      <div style="font-size:13px;color:var(--mut);margin-top:3px">6 days in a row</div>
    </div>
    <div style="display:flex;gap:5px;align-items:flex-end;height:38px">
      <div style="width:9px;height:16px;background:#2A3341;border-radius:3px"></div>
      <div style="width:9px;height:26px;background:#2A3341;border-radius:3px"></div>
      <div style="width:9px;height:12px;background:#2A3341;border-radius:3px"></div>
      <div style="width:9px;height:30px;background:#2A3341;border-radius:3px"></div>
      <div style="width:9px;height:22px;background:#2A3341;border-radius:3px"></div>
      <div style="width:9px;height:34px;background:var(--acc);border-radius:3px"></div>
      <div style="width:9px;height:20px;background:var(--acc);opacity:.45;border-radius:3px"></div>
    </div>
  </div>
</div>
""")

page('Live.dc.html', """
<div class="body">
  <div style="height:14px"></div>
  <div class="pill" style="background:rgba(242,166,90,.14);color:var(--live)"><span class="dot"></span>Blocking now</div>
  <div style="height:14px"></div>
  <h1>Deep work</h1>
  <div class="sub">Started 20:00 &middot; from your calendar</div>

  <div style="height:22px"></div>
  <div class="card" style="padding:22px;display:flex;flex-direction:column;align-items:center;gap:18px">
    <div style="position:relative;width:196px;height:196px">
      <svg width="196" height="196" viewBox="0 0 196 196" style="transform:rotate(-90deg)">
        <circle cx="98" cy="98" r="86" fill="none" stroke="#232B36" stroke-width="11"/>
        <circle cx="98" cy="98" r="86" fill="none" stroke="#F2A65A" stroke-width="11"
          stroke-linecap="round" stroke-dasharray="540" stroke-dashoffset="205"/>
      </svg>
      <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center">
        <div style="font-size:46px;font-weight:700;letter-spacing:-1.6px;line-height:1">1:12</div>
        <div style="font-size:14px;color:var(--mut);margin-top:4px">left &middot; ends 22:00</div>
      </div>
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;justify-content:center">
      <span class="pill">18 apps</span>
      <span class="pill">4 sites</span>
      <span class="pill">Phone + PC</span>
    </div>
  </div>

  <div style="height:18px"></div>
  <div class="card" style="padding:16px 18px;display:flex;gap:12px;align-items:flex-start">
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#8D9AAC" stroke-width="1.8" style="flex:none;margin-top:1px"><rect x="4" y="10" width="16" height="10" rx="3"/><path d="M8 10V7a4 4 0 018 0v3"/></svg>
    <div>
      <div style="font-size:14px;font-weight:600">Locked until it ends</div>
      <div style="font-size:13px;color:var(--mut);margin-top:3px;line-height:1.45">A lock is a promise. You can spend an emergency pass &mdash; you have 2 left this month.</div>
    </div>
  </div>
  <div style="height:12px"></div>
  <button class="btn ghost" style="height:46px;font-size:15px">Use an emergency pass</button>
</div>
""")

page('TodayPower.dc.html', """
<div class="body">
  <div style="height:12px"></div>
  <div style="display:flex;justify-content:space-between;align-items:center">
    <h1 style="font-size:24px">Now</h1>
    <span class="pill" style="background:rgba(124,156,245,.14);color:var(--acc)">Power mode</span>
  </div>
  <div style="height:14px"></div>

  <div class="card" style="padding:18px;position:relative;overflow:hidden;border-color:rgba(242,166,90,.34);
       background:radial-gradient(120% 100% at 0% 0%,rgba(242,166,90,.16) 0%,rgba(242,166,90,.04) 42%,#161B23 78%)">
    <div style="display:flex;align-items:center;gap:9px">
      <span style="width:8px;height:8px;border-radius:50%;background:var(--live);box-shadow:0 0 0 4px rgba(242,166,90,.18)"></span>
      <span style="font-size:11px;font-weight:750;letter-spacing:1.4px;text-transform:uppercase;color:var(--live)">Enforcing</span>
    </div>
    <div style="height:12px"></div>
    <div style="display:flex;align-items:baseline;justify-content:space-between;gap:10px">
      <div style="font-size:21px;font-weight:700;letter-spacing:-.4px">deep&#8209;work</div>
      <div style="font-size:26px;font-weight:700;letter-spacing:-1px;color:var(--live);font-variant-numeric:tabular-nums">1:12:04</div>
    </div>
    <div style="height:12px"></div>
    <div style="height:4px;background:rgba(242,166,90,.16);border-radius:99px;overflow:hidden">
      <div style="width:62%;height:100%;background:linear-gradient(90deg,rgba(242,166,90,.45),var(--live))"></div>
    </div>
    <div style="height:12px"></div>
    <div style="font-size:12px;color:var(--mut);font-family:ui-monospace,monospace">calendar &middot; DSAIT4305 &middot; 20:00&ndash;22:00</div>
    <div style="font-size:12px;color:var(--dim);margin-top:5px">lock: biometric + 15m delay &middot; adopted from PC&#8209;DESK 41s ago</div>
  </div>
  <div style="height:10px"></div>
  <div class="card" style="padding:18px;border-color:rgba(95,211,166,.26);
       background:radial-gradient(120% 100% at 0% 0%,rgba(95,211,166,.11) 0%,rgba(95,211,166,.03) 42%,#161B23 78%)">
    <div style="display:flex;align-items:baseline;justify-content:space-between;gap:10px">
      <div style="font-size:18px;font-weight:700;letter-spacing:-.3px">socials&#8209;budget</div>
      <div style="font-size:18px;font-weight:700;color:var(--ok);font-variant-numeric:tabular-nums">12m<span style="color:var(--dim);font-weight:600;font-size:14px"> / 30m</span></div>
    </div>
    <div style="height:12px"></div>
    <div style="height:4px;background:rgba(95,211,166,.16);border-radius:99px;overflow:hidden">
      <div style="width:40%;height:100%;background:linear-gradient(90deg,rgba(95,211,166,.45),var(--ok))"></div>
    </div>
    <div style="font-size:12px;color:var(--dim);margin-top:11px">budget &middot; shared across 2 devices &middot; resets 00:00</div>
  </div>

  <div style="height:18px"></div>
  <div class="lbl">Upcoming &middot; next 24h</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:0">
    <div style="padding:13px 16px;display:flex;justify-content:space-between;border-bottom:1px solid var(--line)">
      <div style="font-size:14px">Recomp Day 3</div><div style="font-size:13px;color:var(--mut);font-variant-numeric:tabular-nums">07:00&ndash;08:30</div>
    </div>
    <div style="padding:13px 16px;display:flex;justify-content:space-between;border-bottom:1px solid var(--line)">
      <div style="font-size:14px">Weeknights</div><div style="font-size:13px;color:var(--mut);font-variant-numeric:tabular-nums">21:00&ndash;23:59</div>
    </div>
    <div style="padding:13px 16px;display:flex;justify-content:space-between">
      <div style="font-size:14px">Graph ML lecture</div><div style="font-size:13px;color:var(--mut);font-variant-numeric:tabular-nums">13:45&ndash;15:30</div>
    </div>
  </div>

  <div style="height:16px"></div>
  <div style="display:flex;gap:9px">
    <div class="card" style="flex:1;padding:12px 14px"><div class="lbl">Ticks</div><div style="font-size:15px;font-weight:650;margin-top:5px">on time</div></div>
    <div class="card" style="flex:1;padding:12px 14px"><div class="lbl">Peers</div><div style="font-size:15px;font-weight:650;margin-top:5px">1 nearby</div></div>
    <div class="card" style="flex:1;padding:12px 14px"><div class="lbl">Log</div><div style="font-size:15px;font-weight:650;margin-top:5px">14.8 kB</div></div>
  </div>
</div>
""")

page('Plan.dc.html', """
<div class="body">
  <div style="height:14px"></div>
  <div style="display:flex;justify-content:space-between;align-items:flex-start">
    <div>
      <h1>Plan</h1>
      <div class="sub">Three blocks. Two come from your calendar.</div>
    </div>
    <div style="width:42px;height:42px;border-radius:14px;background:var(--acc);display:grid;place-items:center;flex:none;margin-top:4px">
      <svg width="20" height="20" viewBox="0 0 24 24" stroke="#0D1016" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
    </div>
  </div>

  <div style="height:22px"></div>

  <div class="card" style="padding:0;overflow:hidden">
    <div style="padding:18px;display:flex;gap:14px;align-items:center">
      <div style="width:44px;height:44px;border-radius:14px;background:rgba(242,166,90,.16);display:grid;place-items:center;flex:none">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#F2A65A" stroke-width="1.9" stroke-linecap="round"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>
      </div>
      <div style="flex:1">
        <div style="font-size:16px;font-weight:650">Weeknights</div>
        <div style="font-size:13px;color:var(--mut);margin-top:2px">Mon&ndash;Fri &middot; 21:00 &rarr; midnight</div>
      </div>
      <div style="width:44px;height:26px;border-radius:99px;background:var(--acc);position:relative;flex:none">
        <div style="position:absolute;top:3px;right:3px;width:20px;height:20px;border-radius:50%;background:#0D1016"></div>
      </div>
    </div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:18px;display:flex;gap:14px;align-items:center">
      <div style="width:44px;height:44px;border-radius:14px;background:rgba(124,156,245,.16);display:grid;place-items:center;flex:none">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#7C9CF5" stroke-width="1.9" stroke-linecap="round"><rect x="3" y="5" width="18" height="16" rx="4"/><path d="M8 3v4M16 3v4M3 11h18"/></svg>
      </div>
      <div style="flex:1">
        <div style="font-size:16px;font-weight:650">Anything called &ldquo;lecture&rdquo;</div>
        <div style="font-size:13px;color:var(--mut);margin-top:2px">From your calendar &middot; 4 matches this week</div>
      </div>
      <div style="width:44px;height:26px;border-radius:99px;background:var(--acc);position:relative;flex:none">
        <div style="position:absolute;top:3px;right:3px;width:20px;height:20px;border-radius:50%;background:#0D1016"></div>
      </div>
    </div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:18px;display:flex;gap:14px;align-items:center;opacity:.55">
      <div style="width:44px;height:44px;border-radius:14px;background:var(--sur2);display:grid;place-items:center;flex:none">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#8D9AAC" stroke-width="1.9" stroke-linecap="round"><path d="M12 3l7 3v6c0 4.4-3 8.1-7 9-4-.9-7-4.6-7-9V6z"/></svg>
      </div>
      <div style="flex:1">
        <div style="font-size:16px;font-weight:650">Sunday reset</div>
        <div style="font-size:13px;color:var(--mut);margin-top:2px">Sun &middot; all day &middot; paused</div>
      </div>
      <div style="width:44px;height:26px;border-radius:99px;background:var(--sur2);border:1px solid var(--line);position:relative;flex:none">
        <div style="position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#3B4553"></div>
      </div>
    </div>
  </div>

  <div style="height:18px"></div>
  <div class="card" style="padding:16px 18px;display:flex;gap:13px;align-items:center">
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#5FD3A6" stroke-width="1.9" style="flex:none"><path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round"/></svg>
    <div style="font-size:13px;color:var(--mut);line-height:1.45">Your calendar is connected. New events matching a rule block automatically.</div>
  </div>
</div>
""", active='PP')

page('Events.dc.html', """
<div class="body">
  <div style="height:14px"></div>
  <h1 style="font-size:24px">Pick from your calendar</h1>
  <div style="height:14px"></div>
  <div style="height:46px;border-radius:var(--r-ctl);background:var(--sur);border:1px solid var(--line);display:flex;align-items:center;gap:10px;padding:0 14px">
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#8D9AAC" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="M16.5 16.5L21 21" stroke-linecap="round"/></svg>
    <span style="font-size:15px;color:var(--tx)">lect</span><span style="width:1.5px;height:18px;background:var(--acc)"></span>
    <span style="margin-left:auto;font-size:12px;color:var(--dim)">4 of 19</span>
  </div>

  <div style="height:20px"></div>
  <div class="lbl">Today &middot; Tue 9 Sep</div>
  <div style="height:10px"></div>

  <div class="card" style="padding:15px 16px;border-color:rgba(124,156,245,.5);background:rgba(124,156,245,.07)">
    <div style="display:flex;justify-content:space-between;gap:10px">
      <div style="font-size:15px;font-weight:650;line-height:1.3">DSAIT4305 &mdash; Graph Machine <span style="background:rgba(124,156,245,.3);border-radius:3px">Lect</span>ure</div>
      <div style="font-size:13px;color:var(--mut);white-space:nowrap;font-variant-numeric:tabular-nums">13:45</div>
    </div>
    <div style="font-size:12px;color:var(--dim);margin-top:5px">TU Delft &middot; Drebbelweg PC-hall 1</div>
    <div style="height:12px"></div>
    <div style="display:flex;justify-content:space-between;align-items:center">
      <span class="pill" style="background:rgba(124,156,245,.16);color:var(--acc)">Blocks Deep work</span>
      <span style="font-size:13px;color:var(--acc);font-weight:600">Change</span>
    </div>
  </div>

  <div style="height:10px"></div>
  <div class="card" style="padding:15px 16px">
    <div style="display:flex;justify-content:space-between;gap:10px">
      <div style="font-size:15px;font-weight:650;line-height:1.3">Distributed Systems <span style="background:rgba(124,156,245,.3);border-radius:3px">lect</span>ure</div>
      <div style="font-size:13px;color:var(--mut);white-space:nowrap;font-variant-numeric:tabular-nums">16:00</div>
    </div>
    <div style="font-size:12px;color:var(--dim);margin-top:5px">Personal &middot; no location</div>
    <div style="height:12px"></div>
    <div style="display:flex;justify-content:space-between;align-items:center">
      <span style="font-size:13px;color:var(--dim)">Nothing blocked</span>
      <span style="font-size:13px;color:var(--acc);font-weight:600">Block this</span>
    </div>
  </div>

  <div style="height:16px"></div>
  <div class="lbl">Tomorrow &middot; Wed 10 Sep</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:15px 16px">
    <div style="display:flex;justify-content:space-between;gap:10px">
      <div style="font-size:15px;font-weight:650;line-height:1.3">Recomp Day 3: Metabolic</div>
      <div style="font-size:13px;color:var(--mut);white-space:nowrap;font-variant-numeric:tabular-nums">07:00</div>
    </div>
    <div style="font-size:12px;color:var(--dim);margin-top:5px">Marked free in your calendar</div>
  </div>
</div>
""", active='PP')

APPS = [
    ('Instagram', '#E17BA8', True),
    ('YouTube', '#F27272', True),
    ('Reddit', '#F2A65A', True),
    ('WhatsApp', '#5FD3A6', False),
    ('X', '#8D9AAC', True),
    ('Chrome', '#7C9CF5', False),
]


def approw(k, n, c, on):
    brd = 'border-bottom:1px solid var(--line)' if k < len(APPS) - 1 else ''
    sw = ('<div style="width:44px;height:26px;border-radius:99px;background:var(--acc);position:relative;flex:none">'
          '<div style="position:absolute;top:3px;right:3px;width:20px;height:20px;border-radius:50%;background:#0D1016"></div></div>'
          ) if on else (
          '<div style="width:44px;height:26px;border-radius:99px;background:var(--sur2);border:1px solid var(--line);position:relative;flex:none">'
          '<div style="position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#3B4553"></div></div>')
    return ('<div style="padding:13px 16px;display:flex;gap:13px;align-items:center;%s">'
            '<div style="width:36px;height:36px;border-radius:11px;background:%s;display:grid;place-items:center;'
            'color:#0D1016;font-weight:750;font-size:15px;flex:none">%s</div>'
            '<div style="flex:1;font-size:15px;font-weight:550">%s</div>%s</div>' % (brd, c, n[0], n, sw))


def apps_head(seg):
    """Everything above the pane: whose list this is, then the Apps/Websites switch."""
    def half(label, count, on):
        if on:
            return ('<div style="flex:1;height:40px;border-radius:11px;background:var(--acc);color:#0D1016;'
                    'font-size:14px;font-weight:700;display:flex;align-items:center;justify-content:center;gap:7px">'
                    '%s<span style="opacity:.62">%s</span></div>' % (label, count))
        return ('<div style="flex:1;height:40px;border-radius:11px;color:var(--mut);font-size:14px;font-weight:650;'
                'display:flex;align-items:center;justify-content:center;gap:7px">'
                '%s<span style="color:var(--dim)">%s</span></div>' % (label, count))
    return """
<div class="body">
  <div style="height:14px"></div>
  <div class="lbl">Blocked by this profile</div>
  <div style="height:8px"></div>
  <div style="display:flex;gap:8px;align-items:center">
    <span class="pill" style="background:var(--acc);color:#0D1016;height:32px;font-size:13px;font-weight:700">Deep work</span>
    <span class="pill" style="height:32px;font-size:13px">Sunday reset</span>
    <span class="pill" style="height:32px;font-size:13px;color:var(--acc)">+</span>
  </div>
  <div style="height:7px"></div>
  <div style="font-size:12px;color:var(--dim);line-height:1.5">Each profile keeps its own apps and its own websites. Sunday reset blocks 6 apps and 1 site.</div>

  <div style="height:16px"></div>
  <div style="display:flex;background:var(--sur2);border-radius:14px;padding:4px;gap:4px">
    """ + half('Apps', 18, seg == 'apps') + half('Websites', 4, seg == 'web') + """
  </div>
  <div style="height:14px"></div>
"""


FOOT = """
  <div style="height:16px"></div>
  <div style="font-size:12px;color:var(--dim);line-height:1.5">Changes apply the next time Deep work starts &mdash; never to a block already running.</div>
</div>
"""

page('Apps.dc.html', apps_head('apps') + """
  <div class="card" style="padding:0;overflow:hidden">
""" + "".join(approw(k, n, c, on) for k, (n, c, on) in enumerate(APPS)) + """
  </div>
  <div style="height:12px"></div>
  <button class="btn ghost" style="height:44px;font-size:14px">Add an app</button>
""" + FOOT, active='AA')

page('Sites.dc.html', apps_head('web') + """
  <div class="card" style="padding:0;overflow:hidden">
""" + "".join(
    '<div style="padding:14px 16px;display:flex;gap:12px;align-items:center;%s">'
    '<div style="width:36px;height:36px;border-radius:11px;background:var(--sur2);border:1px solid var(--line);'
    'display:grid;place-items:center;flex:none;font-size:13px">%s</div>'
    '<div style="flex:1;min-width:0">'
    '<div style="font-size:14px;font-weight:600;font-family:ui-monospace,monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">%s</div>'
    '<div style="font-size:12px;color:var(--dim);margin-top:2px">%s</div></div>'
    '<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#5D6879" stroke-width="1.9" style="flex:none">'
    '<path d="M6 6l12 12M18 6L6 18"/></svg></div>' % (
        'border-bottom:1px solid var(--line)' if k < 3 else '', ic, v, note)
    for k, (ic, v, note) in enumerate([
        ('&#127760;', 'reddit.com', 'the whole site, on every browser'),
        ('&#127760;', 'news.ycombinator.com', 'the whole site'),
        ('&#42;', '*://*/watch*', 'pattern &mdash; any video page'),
        ('&#9906;', 'gambling', 'word in the address or the title'),
    ])) + """
  </div>
  <div style="height:12px"></div>
  <div class="card" style="padding:0;display:flex;align-items:center;gap:10px;height:48px;padding:0 14px">
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#7C9CF5" stroke-width="2" style="flex:none"><path d="M12 5v14M5 12h14"/></svg>
    <div style="font-size:14px;color:var(--mut);font-family:ui-monospace,monospace">Address, pattern or word&hellip;</div>
  </div>
""" + FOOT, active='AA')

page('Blocked.dc.html', """
<div class="body" style="height:calc(844px - 44px);display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 34px">
  <div style="width:96px;height:96px;border-radius:32px;background:rgba(242,166,90,.12);display:grid;place-items:center">
    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#F2A65A" stroke-width="1.7"><rect x="4" y="10" width="16" height="10" rx="3"/><path d="M8 10V7a4 4 0 018 0v3"/></svg>
  </div>
  <div style="height:28px"></div>
  <div style="font-size:26px;font-weight:700;letter-spacing:-.5px">Instagram is blocked</div>
  <div style="height:12px"></div>
  <div style="font-size:15px;color:var(--mut);line-height:1.55">Deep work is running until <b style="color:var(--tx)">22:00</b>, because your calendar says Graph Machine Learning.</div>
  <div style="height:10px"></div>
  <div style="font-size:44px;font-weight:700;letter-spacing:-1.5px;color:var(--live);font-variant-numeric:tabular-nums">1:12</div>
  <div style="height:34px"></div>
  <button class="btn" style="background:var(--sur2);color:var(--tx)">Back to my home screen</button>
  <div style="height:12px"></div>
  <div style="font-size:13px;color:var(--dim)">Emergency pass &middot; 2 left this month</div>
</div>
""", tabs=False)

page('Settings.dc.html', """
<div class="body">
  <div style="height:14px"></div>
  <h1 style="font-size:24px">Settings</h1>
  <div style="height:16px"></div>

  <div class="card" style="padding:18px">
    <div class="lbl">How much do you want to see?</div>
    <div style="height:12px"></div>
    <div style="display:flex;background:var(--sur2);border-radius:14px;padding:4px;gap:4px">
      <div style="flex:1;height:40px;border-radius:11px;background:var(--acc);color:#0D1016;font-size:14px;font-weight:650;display:grid;place-items:center">Simple</div>
      <div style="flex:1;height:40px;border-radius:11px;color:var(--mut);font-size:14px;font-weight:650;display:grid;place-items:center">Power</div>
    </div>
    <div style="height:14px"></div>
    <div style="font-size:13px;color:var(--mut);line-height:1.5">Four tabs, plain words, no ids. <b style="color:var(--tx)">Power</b> adds Usage, Sync and Health, exact timers, rule syntax and the raw <span style="font-family:ui-monospace,monospace">curfew.toml</span>.</div>
  </div>

  <div style="height:18px"></div>
  <div class="lbl">Curfew can enforce</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:0;overflow:hidden">
    <div style="padding:15px 17px;display:flex;gap:12px;align-items:center;border-bottom:1px solid var(--line)">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#5FD3A6" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M20 6L9 17l-5-5"/></svg>
      <div style="flex:1;font-size:15px">See which app is open</div>
    </div>
    <div style="padding:15px 17px;display:flex;gap:12px;align-items:center;border-bottom:1px solid var(--line)">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#5FD3A6" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M20 6L9 17l-5-5"/></svg>
      <div style="flex:1;font-size:15px">Read your calendar</div>
    </div>
    <div style="padding:15px 17px;display:flex;gap:12px;align-items:center">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#F27272" stroke-width="2.2" stroke-linecap="round" style="flex:none"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.4v.2"/></svg>
      <div style="flex:1">
        <div style="font-size:15px">Draw over other apps</div>
        <div style="font-size:12px;color:var(--mut);margin-top:2px">Without it, the block screen never appears.</div>
      </div>
      <div style="height:32px;padding:0 14px;border-radius:10px;background:var(--acc);color:#0D1016;font-size:13px;font-weight:650;display:grid;place-items:center;flex:none">Fix</div>
    </div>
  </div>

  <div style="height:18px"></div>
  <div class="card" style="padding:16px 18px;display:flex;gap:13px;align-items:flex-start">
    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="#8D9AAC" stroke-width="1.8" style="flex:none;margin-top:1px"><path d="M12 3l7 3v6c0 4.4-3 8.1-7 9-4-.9-7-4.6-7-9V6z"/></svg>
    <div style="font-size:13px;color:var(--mut);line-height:1.5">Curfew has no internet permission at all. Your devices sync directly to each other &mdash; no account, no server.</div>
  </div>
</div>
""", active='SS')

page('System.dc.html', """
<div style="padding:32px 34px;width:820px;height:1020px;overflow:hidden">
  <div style="font-size:13px;font-weight:700;letter-spacing:1.6px;text-transform:uppercase;color:var(--dim)">Curfew &mdash; reinvented</div>
  <div style="height:10px"></div>
  <div style="font-size:34px;font-weight:700;letter-spacing:-.8px">Calm dark, one accent, one promise per screen</div>
  <div style="height:26px"></div>

  <div style="display:flex;gap:12px">
""" + "".join(
    '<div style="flex:1"><div style="height:74px;border-radius:16px;background:%s;border:1px solid var(--line)"></div>'
    '<div style="font-size:12px;font-weight:650;margin-top:8px">%s</div>'
    '<div style="font-size:11px;color:var(--dim);font-family:ui-monospace,monospace">%s</div></div>' % (h, n, h)
    for n, h in [('bg', '#0D1016'), ('surface', '#161B23'), ('raised', '#1D242E'), ('line', '#2A3341'),
                 ('accent', '#7C9CF5'), ('live', '#F2A65A'), ('ok', '#5FD3A6'), ('stop', '#F27272')]) + """
  </div>

  <div style="height:34px"></div>
  <div style="display:flex;gap:26px">
    <div style="flex:1">
      <div class="lbl">Type</div><div style="height:12px"></div>
      <div style="font-size:28px;font-weight:700;letter-spacing:-.6px">Display 28/700</div>
      <div style="font-size:16px;font-weight:650;margin-top:12px">Title 16/650</div>
      <div style="font-size:15px;margin-top:10px">Body 15/400 &mdash; sentences, not labels.</div>
      <div style="font-size:13px;color:var(--mut);margin-top:10px">Secondary 13/400 in muted</div>
      <div class="lbl" style="margin-top:12px">Overline 11/700 tracked</div>
      <div style="font-size:15px;font-family:ui-monospace,monospace;margin-top:12px">Mono 15 &mdash; 1:12:04, curfew.toml</div>
    </div>
    <div style="flex:1">
      <div class="lbl">Rules</div><div style="height:12px"></div>
      <ul style="font-size:14px;color:var(--mut);line-height:1.75;padding-left:18px">
        <li>Cards 22px radius, controls 14px, pills 999px.</li>
        <li>20px page gutter, 10px between cards, 18px inside them.</li>
        <li>Amber means a block is live. Blue means you can touch it. Nothing else is coloured.</li>
        <li>No id ever reaches the screen &mdash; profiles and events show their names.</li>
        <li>Every empty state says what happens next, never just &ldquo;nothing here&rdquo;.</li>
        <li>Simple hides tabs, not powers: every Power screen is reachable from Settings.</li>
      </ul>
    </div>
  </div>

  <div style="height:30px"></div>
  <div class="lbl">Navigation &mdash; simple (4) vs power (7)</div>
  <div style="height:12px"></div>
  <div style="display:flex;gap:16px">
    <div style="flex:1;background:var(--sur);border:1px solid var(--line);border-radius:18px;padding:14px 8px;display:flex">
""" + "".join(
    '<div style="flex:1;text-align:center;font-size:12px;font-weight:600;color:%s">%s</div>' %
    ('var(--tx)' if i == 0 else 'var(--dim)', n)
    for i, n in enumerate(['Today', 'Plan', 'Apps', 'Settings'])) + """
    </div>
    <div style="flex:1;background:var(--sur);border:1px solid var(--line);border-radius:18px;padding:14px 6px;display:flex">
""" + "".join(
    '<div style="flex:1;text-align:center;font-size:11px;font-weight:600;color:%s">%s</div>' %
    ('var(--tx)' if i == 0 else 'var(--dim)', n)
    for i, n in enumerate(['Now', 'Plan', 'Events', 'Apps', 'Usage', 'Sync', 'Health'])) + """
    </div>
  </div>
</div>
""", tabs=False)

exec(open('_profiles.py', encoding='utf-8').read())

print('built')


# --- Revision 2 ----------------------------------------------------------------------------------
#
# Drawn before the code, on instruction. Four things the phone got wrong: a bar that changed shape
# between modes, usage written as a table instead of a sentence, syncing that a Simple user could
# not see at all, and permissions asked for in prose instead of by the system.

page('NavGlass.dc.html', """
<div class="body under">
  <div style="height:14px"></div>
  <h1>One bar.<br>One material.</h1>
  <div class="sub">The same five tabs in both modes. Power adds depth inside a screen, never
    another tab: a bar that changes shape between modes is a mode nobody turns on.</div>

  <div style="height:24px"></div>
  <div class="lbl">The slab, close up</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:16px">
    <div style="height:62px;border-radius:26px;display:flex;align-items:center;justify-content:space-between;padding:0 6px;
      background:linear-gradient(180deg,rgba(255,255,255,.06),rgba(255,255,255,0)),rgba(22,27,35,.86);
      border:1px solid rgba(255,255,255,.09);box-shadow:0 18px 40px rgba(0,0,0,.55)">
      <div style="flex:1;text-align:center;color:var(--acc);font-size:10px;font-weight:600">
        <div style="width:30px;height:30px;margin:0 auto 3px;border-radius:11px;background:rgba(124,156,245,.14)"></div>Now</div>
      <div style="flex:1;text-align:center;color:var(--dim);font-size:10px;font-weight:600">
        <div style="width:30px;height:30px;margin:0 auto 3px"></div>Plan</div>
      <div style="flex:1;text-align:center;color:var(--dim);font-size:10px;font-weight:600">
        <div style="width:30px;height:30px;margin:0 auto 3px"></div>Events</div>
      <div style="flex:1;text-align:center;color:var(--dim);font-size:10px;font-weight:600">
        <div style="width:30px;height:30px;margin:0 auto 3px"></div>Apps</div>
      <div style="flex:1;text-align:center;color:var(--dim);font-size:10px;font-weight:600">
        <div style="width:30px;height:30px;margin:0 auto 3px"></div>Settings</div>
    </div>
  </div>

  <div style="height:18px"></div>
  <div class="lbl">Why it reads as glass</div>
  <div style="height:10px"></div>
  <div class="card" style="font-size:13px;line-height:1.7;color:var(--mut)">
    A translucent ground, so the page moving underneath shows through.<br>
    A hairline along the top edge, catching light.<br>
    A gradient that shades the belly, so the slab has a near side and a far one.<br>
    A long soft shadow beneath, so it sits <i>above</i> the page rather than in it.<br>
    <span style="color:var(--tx)">Sheets, dialogs and the block screen use the same four rules.</span>
  </div>

  <div style="height:16px"></div>
  <div class="card" style="border-color:rgba(124,156,245,.3)">
    <div style="font-size:13px;line-height:1.6;color:var(--mut)">Usage, Health and Devices left the
      bar. They live one tap into Settings, for everyone — which is where a Simple user can finally
      find syncing too.</div>
  </div>
</div>
""", active='TT')


page('UsageSimple.dc.html', """
<div class="body under">
  <div style="height:14px"></div>
  <h1>You got 11 hours<br>back this week.</h1>
  <div class="sub">Compared with the four weeks before you started.</div>

  <div style="height:22px"></div>
  <div class="card" style="padding:20px">
    <div style="font-size:11px;font-weight:700;letter-spacing:1.4px;color:var(--dim)">SCREEN TIME, DAILY AVERAGE</div>
    <div style="height:16px"></div>
    <div style="display:flex;align-items:center;gap:14px">
      <div style="flex:1">
        <div style="font-size:13px;color:var(--mut);margin-bottom:6px">Before Curfew</div>
        <div style="height:12px;border-radius:6px;background:var(--line)"></div>
        <div style="font-size:22px;font-weight:700;margin-top:8px;color:var(--mut)">6h 10m</div>
      </div>
      <div style="flex:1">
        <div style="font-size:13px;color:var(--mut);margin-bottom:6px">Now</div>
        <div style="height:12px;border-radius:6px;background:var(--line);position:relative">
          <div style="position:absolute;left:0;top:0;bottom:0;width:63%;border-radius:6px;background:var(--ok)"></div>
        </div>
        <div style="font-size:22px;font-weight:700;margin-top:8px;color:var(--ok)">3h 52m</div>
      </div>
    </div>
    <div style="height:14px"></div>
    <div style="font-size:13px;line-height:1.6;color:var(--mut)">That is <span style="color:var(--tx)">2h 18m a
      day</span> you are not spending on a screen. Curfew only counts apps a rule of yours covers —
      it keeps no record of everything you open.</div>
  </div>

  <div style="height:12px"></div>
  <div class="card" style="padding:18px;display:flex;align-items:center;justify-content:space-between">
    <div>
      <div style="font-size:19px;font-weight:700">6 days in a row</div>
      <div style="font-size:13px;color:var(--mut);margin-top:3px">Best so far: 11. Today has not
        finished, so it does not count against you yet.</div>
    </div>
  </div>

  <div style="height:20px"></div>
  <div class="lbl">Where it went today</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:16px 18px">
    <div style="display:flex;justify-content:space-between;font-size:14px;font-weight:600"><span>Instagram</span><span style="color:var(--mut)">48m</span></div>
    <div style="height:6px;border-radius:3px;background:var(--line);margin:8px 0 14px;position:relative"><div style="position:absolute;inset:0;width:80%;border-radius:3px;background:var(--acc)"></div></div>
    <div style="display:flex;justify-content:space-between;font-size:14px;font-weight:600"><span>YouTube</span><span style="color:var(--mut)">31m</span></div>
    <div style="height:6px;border-radius:3px;background:var(--line);margin:8px 0 0;position:relative"><div style="position:absolute;inset:0;width:52%;border-radius:3px;background:var(--acc)"></div></div>
  </div>
  <div style="height:10px"></div>
  <div style="font-size:12px;color:var(--dim);line-height:1.6">The per-app table and the full log of
    what Curfew did are the Power view of this same screen — not the first thing anyone sees.</div>
</div>
""", active='TT')


page('SettingsSync.dc.html', """
<div class="body under">
  <div style="height:14px"></div>
  <h1>Settings</h1>
  <div style="height:20px"></div>

  <div class="lbl">Your other devices</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:0">
    <div style="padding:16px 18px;display:flex;align-items:center;justify-content:space-between">
      <div>
        <div style="font-size:15px;font-weight:600">Syncing with 1 device</div>
        <div style="font-size:13px;color:var(--mut);margin-top:3px">Last synced 4 minutes ago</div>
      </div>
      <div class="pill" style="background:rgba(95,211,166,.14);color:var(--ok)"><span class="dot" style="background:var(--ok)"></span>On</div>
    </div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:14px 18px;display:flex;align-items:center;justify-content:space-between">
      <div style="font-size:14px">Satwik's laptop</div>
      <div style="font-size:12px;color:var(--dim)">on this network</div>
    </div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:14px 18px;font-size:14px;color:var(--acc);font-weight:600">Add a device</div>
  </div>
  <div style="height:8px"></div>
  <div style="font-size:12px;color:var(--dim);line-height:1.6">Devices talk to each other directly on
    your network. Nothing goes to a server, because there is no server.</div>

  <div style="height:20px"></div>
  <div class="lbl">More</div>
  <div style="height:10px"></div>
  <div class="card" style="padding:0">
    <div style="padding:15px 18px;display:flex;justify-content:space-between;font-size:15px"><span>Where your time went</span><span style="color:var(--dim)">&rsaquo;</span></div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:15px 18px;display:flex;justify-content:space-between;font-size:15px"><span>Is it working?</span><span style="color:var(--ok);font-size:13px">All good &rsaquo;</span></div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:15px 18px;display:flex;justify-content:space-between;font-size:15px"><span>Show more detail everywhere</span>
      <span style="width:44px;height:26px;border-radius:999px;background:var(--line);position:relative;display:inline-block">
        <span style="position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:var(--dim)"></span></span></div>
  </div>
  <div style="height:8px"></div>
  <div style="font-size:12px;color:var(--dim);line-height:1.6">That last switch is all Power mode is
    now: more inside these screens. It never moves a tab.</div>
</div>
""", active='SS')


page('Permission.dc.html', """
<div class="body under">
  <div style="height:14px"></div>
  <h1>Is it working?</h1>
  <div class="sub" style="color:var(--bad)">Nothing is being blocked. Curfew cannot see which app is
    in front, so it cannot put anything in its way.</div>

  <div style="height:20px"></div>
  <div class="card" style="padding:0">
    <div style="padding:16px 18px">
      <div style="display:flex;align-items:center;gap:11px">
        <span style="color:var(--bad);font-weight:700">!</span>
        <div style="flex:1;font-size:15px;font-weight:600">See which app is in front</div>
        <div style="height:32px;padding:0 15px;border-radius:10px;background:var(--acc);color:#0D1016;font-size:13px;font-weight:700;display:flex;align-items:center">Allow</div>
      </div>
      <div style="font-size:12px;color:var(--mut);margin:7px 0 0 30px;line-height:1.6">One tap opens
        Android's own switch for this, already scrolled to Curfew.</div>
    </div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:16px 18px">
      <div style="display:flex;align-items:center;gap:11px">
        <span style="color:var(--ok);font-weight:700">&#10003;</span>
        <div style="flex:1;font-size:15px;font-weight:600">Notifications</div>
      </div>
      <div style="font-size:12px;color:var(--mut);margin:7px 0 0 30px;line-height:1.6">Asked with the
        system dialog, like every other app asks.</div>
    </div>
    <div style="height:1px;background:var(--line)"></div>
    <div style="padding:16px 18px">
      <div style="display:flex;align-items:center;gap:11px">
        <span style="color:var(--dim);font-weight:700">&ndash;</span>
        <div style="flex:1;font-size:15px;font-weight:600">Make Curfew harder to uninstall</div>
        <div style="height:32px;padding:0 15px;border-radius:10px;border:1px solid var(--line);color:var(--tx);font-size:13px;font-weight:600;display:flex;align-items:center">Read first</div>
      </div>
      <div style="font-size:12px;color:var(--mut);margin:7px 0 0 30px;line-height:1.6">The one thing
        here that gets a page of explanation instead of a tap, because it is the one that deserves
        it. Optional, and Curfew works without it.</div>
    </div>
  </div>

  <div style="height:16px"></div>
  <div class="card">
    <div style="font-size:13px;line-height:1.7;color:var(--mut)">Every other permission is a system
      dialog or a deep link straight into the page that grants it. A wall of prose with an
      &ldquo;Open App info&rdquo; button is how an app teaches people to ignore it.</div>
  </div>
</div>
""", active='SS')

# What happened, said out loud.
#
# The old script printed "built" and nothing else, so "I built 19 artboards" and "I skipped the only
# one that needed my attention" looked identical from the outside. Drift you cannot see is drift you
# do not fix.
if SKIPPED:
    print('built (with %d artboard(s) left alone)' % len(SKIPPED))
    print('')
    print('These differ from what this script generates, so they were NOT rewritten:')
    for name in SKIPPED:
        print('  ' + name)
    print('')
    print('A committed artboard differing from its generator usually means the design moved on and')
    print('this script did not. Look at the diff before deciding which one is right:')
    print('    git diff -- ' + ' '.join(SKIPPED))
    print('')
    print('Re-run with --force to overwrite them anyway.')
else:
    print('built')
# Every artboard on disk must be on the canvas.
#
# This is the check that would have caught the orphan. `EditWindow.dc.html` sat in `design/` for who
# knows how long, absent from `canvas.json` — which means it was invisible to anyone reviewing the
# design, since the canvas is where the design is reviewed. It is the one artboard this script does not
# generate (it is hand-written), which is exactly why it was the one that got forgotten: nothing
# produced it, so nothing noticed it was unlisted.
#
# Run here rather than in a test suite because this script owns the artboard lifecycle and has no test
# harness; a check that lives nowhere is a check that does not run.
#
# Note which direction catches what, because the two are not symmetric. "On disk but unlisted" catches
# an artboard somebody forgot to place, and it is the direction that would have caught EditWindow.
# "Listed but gone" can only ever fire for a *hand-written* artboard, since anything this script
# generates has just been regenerated by the time the check runs — confirmed by deleting
# `Permission.dc.html` and watching the check stay silent because the script had put it back.
import glob
import json

listed = {a['file'] for a in json.load(open('canvas.json', encoding='utf-8'))['artboards']}
present = {os.path.basename(p) for p in glob.glob('*.dc.html')}

for missing in sorted(present - listed):
    print('')
    print('WARNING: %s exists but canvas.json does not list it, so it is invisible on the canvas.' % missing)
    print('         Add it to canvas.json (x, y, w, h) — see the neighbours in the last row.')
for gone in sorted(listed - present):
    print('')
    print('WARNING: canvas.json lists %s, which is not on disk.' % gone)