# Profile creation, profile editing, and the triggers that are not the calendar.
# Executed from build.py, so `page`, `TOK` and friends are already defined.

TRIGGERS = [
    ('#7C9CF5', 'M3 5h18M3 12h18M3 19h12', 'A repeating schedule',
     'Weeknights 21:00 to midnight, every Mon&ndash;Fri.'),
    ('#F2A65A', 'M12 7.5V12l3 2', 'A timer I start myself',
     'Tap once, block for 90 minutes. Nothing scheduled.'),
    ('#5FD3A6', 'M3 5h18v16H3zM8 3v4M16 3v4', 'Anything in my calendar',
     'Events whose title contains &ldquo;lecture&rdquo;.'),
    ('#E17BA8', 'M12 21a9 9 0 100-18 9 9 0 000 18zM12 12l4-2', 'A daily budget',
     'Half an hour of socials a day, then they close.'),
    ('#8D9AAC', 'M5 12h14M12 5v14', 'Always on',
     'Never unblocked, unless you spend a pass.'),
]


def trigger_row(k, c, d, title, sub, chosen=False):
    box = ('background:%s;border:1px solid %s' % (c + '22', c + '55')) if chosen \
        else 'background:var(--sur2);border:1px solid var(--line)'
    mark = ('<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="%s" stroke-width="2.4" '
            'stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M20 6L9 17l-5-5"/></svg>' % c) \
        if chosen else \
        ('<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#5D6879" stroke-width="2" '
         'stroke-linecap="round" style="flex:none"><path d="M9 6l6 6-6 6"/></svg>')
    return ('<div style="padding:15px 16px;display:flex;gap:13px;align-items:center;%s">'
            '<div style="width:40px;height:40px;border-radius:13px;%s;display:grid;place-items:center;flex:none">'
            '<svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="%s" stroke-width="1.9" '
            'stroke-linecap="round" stroke-linejoin="round"><path d="%s"/></svg></div>'
            '<div style="flex:1"><div style="font-size:15px;font-weight:650">%s</div>'
            '<div style="font-size:12px;color:var(--mut);margin-top:3px;line-height:1.4">%s</div></div>%s</div>'
            % ('border-bottom:1px solid var(--line)' if k < len(TRIGGERS) - 1 else '',
               box, c, d, title, sub, mark))


def stepper(label, value, note=''):
    """Every fixed number in the app is one of these -- nothing is baked in."""
    return ('<div style="padding:14px 16px;display:flex;gap:12px;align-items:center;border-bottom:1px solid var(--line)">'
            '<div style="flex:1"><div style="font-size:15px">%s</div>%s</div>'
            '<div style="display:flex;align-items:center;gap:2px;background:var(--sur2);border-radius:11px;padding:3px;flex:none">'
            '<div style="width:30px;height:30px;border-radius:9px;display:grid;place-items:center;color:var(--mut);font-size:17px;font-weight:600">&minus;</div>'
            '<div style="min-width:62px;text-align:center;font-size:14px;font-weight:700;font-variant-numeric:tabular-nums">%s</div>'
            '<div style="width:30px;height:30px;border-radius:9px;display:grid;place-items:center;background:var(--acc);color:#0D1016;font-size:17px;font-weight:600">+</div>'
            '</div></div>'
            % (label, ('<div style="font-size:12px;color:var(--dim);margin-top:3px">%s</div>' % note) if note else '', value))


BACKBAR = """
<div style="display:flex;align-items:center;gap:14px;height:44px">
  <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="#EDF1F7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 6l-6 6 6 6"/></svg>
  <div style="font-size:16px;font-weight:650">%s</div>
</div>
"""


# --- 1. Naming a new profile ------------------------------------------------
page('ProfileNew.dc.html', """
<div class="body">
""" + BACKBAR % 'New profile' + """
  <div style="height:10px"></div>
  <h1 style="font-size:26px">What should we<br>call this one?</h1>
  <div class="sub">A name you would say out loud. You can change it whenever.</div>

  <div style="height:22px"></div>
  <div class="card" style="padding:0;height:58px;display:flex;align-items:center;padding:0 18px;gap:12px;border-color:var(--acc)">
    <div style="font-size:19px;font-weight:600">Thesis writing</div>
    <div style="width:2px;height:23px;background:var(--acc)"></div>
  </div>

  <div style="height:20px"></div>
  <div class="lbl">Colour</div>
  <div style="height:10px"></div>
  <div style="display:flex;gap:11px">
""" + "".join(
    '<div style="width:44px;height:44px;border-radius:14px;background:%s;%s"></div>'
    % (c, 'box-shadow:0 0 0 2px #0D1016,0 0 0 4px %s' % c if k == 1 else '')
    for k, c in enumerate(['#7C9CF5', '#F2A65A', '#5FD3A6', '#E17BA8', '#F27272', '#8D9AAC'])) + """
  </div>

  <div style="height:22px"></div>
  <div class="lbl">Or start from one of these</div>
  <div style="height:10px"></div>
  <div style="display:flex;gap:8px;flex-wrap:wrap">
    <span class="pill" style="height:34px;font-size:13px">&#128218; Study</span>
    <span class="pill" style="height:34px;font-size:13px">&#127769; Sleep</span>
    <span class="pill" style="height:34px;font-size:13px">&#128241; Socials diet</span>
    <span class="pill" style="height:34px;font-size:13px">&#127968; Weekend</span>
  </div>

  <div style="height:26px"></div>
  <button class="btn">Next &mdash; when should it run?</button>
</div>
""", tabs=False)


# --- 2. Choosing what starts it --------------------------------------------
page('Triggers.dc.html', """
<div class="body">
""" + BACKBAR % 'Thesis writing' + """
  <div style="height:10px"></div>
  <h1 style="font-size:26px">When should<br>it run?</h1>
  <div class="sub">Pick as many as you like. They stack &mdash; a calendar rule and a nightly schedule can both switch the same profile on.</div>

  <div style="height:20px"></div>
  <div class="card" style="padding:0;overflow:hidden">
""" + "".join(trigger_row(k, c, d, t, s, chosen=(k in (0, 1)))
              for k, (c, d, t, s) in enumerate(TRIGGERS)) + """
  </div>

  <div style="height:16px"></div>
  <div style="font-size:12px;color:var(--dim);line-height:1.5">Two chosen. Whichever starts first wins, and the block ends when the last one is done.</div>
  <div style="height:14px"></div>
  <button class="btn">Set up the schedule</button>
</div>
""", tabs=False)


# --- 3. The timer -- start something now, no schedule at all ----------------
page('Timer.dc.html', """
<div class="body">
""" + BACKBAR % 'Start now' + """
  <div style="height:6px"></div>
  <div style="display:flex;flex-direction:column;align-items:center">
    <div style="position:relative;width:236px;height:236px;margin-top:6px">
      <svg width="236" height="236" viewBox="0 0 236 236" style="transform:rotate(-90deg)">
        <circle cx="118" cy="118" r="104" fill="none" stroke="#232B36" stroke-width="12"/>
        <circle cx="118" cy="118" r="104" fill="none" stroke="#F2A65A" stroke-width="12" stroke-linecap="round"
          stroke-dasharray="653" stroke-dashoffset="196"/>
      </svg>
      <div style="position:absolute;left:16px;top:16px;width:28px;height:28px;border-radius:50%;background:var(--live);
           box-shadow:0 0 0 5px rgba(242,166,90,.2);transform:translate(84px,-14px)"></div>
      <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center">
        <div style="font-size:52px;font-weight:700;letter-spacing:-2px;line-height:1;font-variant-numeric:tabular-nums">1:30</div>
        <div style="font-size:14px;color:var(--mut);margin-top:6px">until 22:34</div>
      </div>
    </div>
    <div style="height:16px"></div>
    <div style="font-size:13px;color:var(--dim)">Drag the dial, or pick one</div>
    <div style="height:11px"></div>
    <div style="display:flex;gap:8px">
      <span class="pill" style="height:34px;font-size:13px">25m</span>
      <span class="pill" style="height:34px;font-size:13px">50m</span>
      <span class="pill" style="height:34px;font-size:13px;background:var(--acc);color:#0D1016;font-weight:700">1h 30m</span>
      <span class="pill" style="height:34px;font-size:13px">3h</span>
    </div>
  </div>

  <div style="height:18px"></div>
  <div class="card" style="padding:15px 17px;display:flex;gap:13px;align-items:center">
    <div style="width:34px;height:34px;border-radius:11px;background:rgba(124,156,245,.16);display:grid;place-items:center;flex:none;
         color:var(--acc);font-weight:750;font-size:14px">T</div>
    <div style="flex:1">
      <div style="font-size:15px;font-weight:600">Thesis writing</div>
      <div style="font-size:12px;color:var(--mut);margin-top:2px">18 apps, 4 sites</div>
    </div>
    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#5D6879" stroke-width="2" stroke-linecap="round" style="flex:none"><path d="M9 6l6 6-6 6"/></svg>
  </div>
  <div style="height:12px"></div>
  <button class="btn" style="background:var(--live)">Lock it in for 1h 30m</button>
</div>
""", tabs=False)


# --- 4. Editing a profile -- every fixed value is a control -----------------
page('ProfileEdit.dc.html', """
<div class="body" style="overflow:hidden">
""" + BACKBAR % 'Thesis writing' + """
  <div style="height:8px"></div>

  <div class="lbl">Starts because of</div>
  <div style="height:9px"></div>
  <div style="display:flex;gap:8px;flex-wrap:wrap">
    <span class="pill" style="height:32px;font-size:13px;background:rgba(124,156,245,.16);color:var(--acc)">Mon&ndash;Fri 21:00 &rarr; 00:00</span>
    <span class="pill" style="height:32px;font-size:13px;background:rgba(242,166,90,.16);color:var(--live)">Timer</span>
    <span class="pill" style="height:32px;font-size:13px;color:var(--acc)">+ Add</span>
  </div>

  <div style="height:18px"></div>
  <div class="lbl">How hard is it to get out</div>
  <div style="height:9px"></div>
  <div class="card" style="padding:0;overflow:hidden">
    <div style="padding:14px 16px;border-bottom:1px solid var(--line)">
      <div style="display:flex;background:var(--sur2);border-radius:12px;padding:3px;gap:3px">
        <div style="flex:1;height:34px;border-radius:10px;color:var(--mut);font-size:13px;font-weight:650;display:grid;place-items:center">Ask nicely</div>
        <div style="flex:1;height:34px;border-radius:10px;background:var(--acc);color:#0D1016;font-size:13px;font-weight:700;display:grid;place-items:center">Fingerprint</div>
        <div style="flex:1;height:34px;border-radius:10px;color:var(--mut);font-size:13px;font-weight:650;display:grid;place-items:center">No way out</div>
      </div>
    </div>
""" + stepper('Wait before it unlocks', '15 min', 'A cooling-off period you cannot skip.') +
     stepper('Emergency passes a month', '2') +
     stepper('Grace at the start', '30 sec', 'Time to close what you were doing.').replace(
         'border-bottom:1px solid var(--line)', '') + """
  </div>

  <div style="height:16px"></div>
  <div class="lbl">Limits while it runs</div>
  <div style="height:9px"></div>
  <div class="card" style="padding:0;overflow:hidden">
""" + stepper('Daily budget for these apps', 'off', 'Set minutes to allow a little instead of none.') +
     stepper('Opens allowed per day', '3').replace('border-bottom:1px solid var(--line)', '') + """
  </div>

  <div style="height:16px"></div>
  <div style="display:flex;gap:10px">
    <button class="btn ghost" style="height:46px;font-size:14px;color:var(--bad);border-color:rgba(242,114,114,.35)">Delete</button>
    <button class="btn" style="height:46px;font-size:14px">Save</button>
  </div>
</div>
""", tabs=False)
