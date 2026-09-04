//! What the trusted clock promises: moving the device clock never shortens a lock.

use curfew_core::{ClockWitness, Reading, Timestamp};

const START: Timestamp = 1_788_510_600; // 2026-09-04 09:30 Europe/London.

fn reading(wall: Timestamp, uptime: i64, boot_id: u64) -> Reading {
    Reading { wall, uptime, boot_id }
}

fn witness() -> ClockWitness {
    ClockWitness::new(reading(START, 1_000, 7))
}

#[test]
fn time_passing_normally_is_simply_time_passing() {
    let mut clock = witness();
    let verdict = clock.observe(reading(START + 300, 1_300, 7));
    assert_eq!(verdict.now, START + 300);
    assert!(!verdict.tampered());
    assert_eq!(verdict.unverified, 0);
}

#[test]
fn a_clock_wound_forward_while_running_buys_nothing() {
    let mut clock = witness();

    // Sixty seconds really passed; the user claims two hours.
    let verdict = clock.observe(reading(START + 7_200, 1_060, 7));

    assert_eq!(verdict.now, START + 60, "the stolen time was credited to the lock");
    assert_eq!(verdict.refused_forward, 7_140);
    assert!(verdict.tampered());
}

#[test]
fn a_timer_lock_still_has_its_full_time_left_after_the_clock_is_wound_forward() {
    // The whole point, stated as the attack: a two-hour lock, and a user who sets the clock
    // forward two hours one minute in.
    let ends_at = START + 2 * 3_600;
    let mut clock = witness();

    let verdict = clock.observe(reading(START + 4 * 3_600, 1_060, 7));

    assert!(verdict.now < ends_at, "the clock change ended the lock");
    assert_eq!(ends_at - verdict.now, 2 * 3_600 - 60, "the lock lost time it should have kept");
}

#[test]
fn a_clock_wound_backwards_while_running_is_refused_too() {
    let mut clock = witness();
    let verdict = clock.observe(reading(START - 7_200, 1_060, 7));
    assert_eq!(verdict.now, START + 60);
    // Two hours lost, plus the minute that really passed and which the wall clock never showed.
    assert_eq!(verdict.refused_backward, 7_260);
}

#[test]
fn trusted_time_never_goes_backwards_however_the_clock_is_moved() {
    let mut clock = witness();
    let mut previous = clock.now();
    for (wall, uptime, boot) in [
        (START + 60, 1_060, 7),
        (START - 100_000, 1_120, 7),
        (START + 500_000, 1_180, 7),
        (START, 10, 8),
        (START - 900_000, 20, 8),
        (START + 30, 80, 8),
    ] {
        let verdict = clock.observe(reading(wall, uptime, boot));
        assert!(verdict.now >= previous, "trusted time moved backwards");
        previous = verdict.now;
    }
}

#[test]
fn ntp_drift_is_not_tampering() {
    let mut clock = witness();
    let verdict = clock.observe(reading(START + 300 + 5, 1_300, 7));
    assert!(!verdict.tampered());
    assert_eq!(verdict.now, START + 300, "trusted time follows uptime, not the correction");
}

#[test]
fn time_across_a_reboot_is_credited_and_marked_unverified() {
    let mut clock = witness();

    // Switched off for eight hours: a new boot id and an uptime that starts again from nothing.
    let verdict = clock.observe(reading(START + 8 * 3_600, 30, 8));

    assert_eq!(verdict.now, START + 8 * 3_600);
    assert_eq!(verdict.unverified, 8 * 3_600);
    assert!(!verdict.tampered(), "honest downtime must not be called tampering");
}

#[test]
fn a_reboot_detected_by_uptime_alone_is_still_a_reboot() {
    // A platform that cannot supply a real boot id passes a constant. Uptime running backwards is
    // then the only evidence a restart happened, and it has to be enough.
    let mut clock = witness();
    let verdict = clock.observe(reading(START + 3_600, 20, 7));
    assert_eq!(verdict.unverified, 3_600);
    assert_eq!(verdict.now, START + 3_600);
}

#[test]
fn a_clock_wound_backwards_across_a_reboot_is_refused() {
    let mut clock = witness();
    let verdict = clock.observe(reading(START - 8 * 3_600, 30, 8));
    assert_eq!(verdict.now, START, "being switched off cannot make the clock earlier");
    assert_eq!(verdict.refused_backward, 8 * 3_600);
}

#[test]
fn the_witness_survives_being_written_down_and_read_back() {
    let mut clock = witness();
    clock.observe(reading(START + 600, 1_600, 7));

    let json = serde_json::to_string(&clock).expect("a witness must serialize");
    let mut restored: ClockWitness = serde_json::from_str(&json).expect("a witness must load");

    assert_eq!(restored.now(), clock.now());
    // And it keeps refusing what it refused before the process died.
    assert_eq!(restored.observe(reading(START + 90_000, 1_660, 7)).refused_forward, 89_340);
}

#[test]
fn repeated_readings_of_the_same_instant_change_nothing() {
    let mut clock = witness();
    let first = clock.observe(reading(START + 60, 1_060, 7));
    let second = clock.observe(reading(START + 60, 1_060, 7));
    assert_eq!(first.now, second.now);
    assert!(!second.tampered());
}

// The promise stated as a property rather than as examples: whatever a device does to its two
// clocks — jumps, reboots, repeated readings, values chosen adversarially — trusted time only ever
// moves forward, and within a single boot it never moves faster than uptime did.
proptest::proptest! {
    #[test]
    fn no_sequence_of_readings_can_move_trusted_time_backwards(
        readings in proptest::collection::vec(
            (START - 1_000_000..START + 1_000_000i64, 0..500_000i64, 0..3u64),
            1..40,
        )
    ) {
        let mut clock = witness();
        let mut previous = clock.now();
        let mut last = reading(START, 1_000, 7);

        for (wall, uptime, boot_id) in readings {
            let next = reading(wall, uptime, boot_id);
            let verdict = clock.observe(next);

            proptest::prop_assert!(verdict.now >= previous, "trusted time moved backwards");

            let same_boot = next.boot_id == last.boot_id && next.uptime >= last.uptime;
            if same_boot {
                proptest::prop_assert!(
                    verdict.now - previous <= next.uptime - last.uptime,
                    "trusted time ran ahead of the monotonic clock",
                );
            }

            previous = verdict.now;
            last = next;
        }
    }
}
