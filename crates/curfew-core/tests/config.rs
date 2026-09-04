//! Golden-file tests for the `curfew.toml` document. A config written by an older version must
//! keep loading, and a config we write must load back identically (GAPS E3).

use curfew_core::{Config, CONFIG_SCHEMA_VERSION};

const GOLDEN: &str = include_str!("golden/example.toml");

#[test]
fn golden_config_parses() {
    let cfg = Config::from_toml(GOLDEN).expect("golden config must parse");
    assert_eq!(cfg.schema_version, CONFIG_SCHEMA_VERSION);
    assert_eq!(cfg.profiles.len(), 1);
    assert_eq!(cfg.profiles[0].rules.len(), 4);
}

#[test]
fn config_round_trips() {
    let cfg = Config::from_toml(GOLDEN).unwrap();
    let written = cfg.to_toml().unwrap();
    let reparsed = Config::from_toml(&written).unwrap();
    assert_eq!(cfg, reparsed);
}

#[test]
fn refuses_a_config_from_the_future() {
    let newer = GOLDEN.replace("schema_version = 0", "schema_version = 9999");
    assert!(Config::from_toml(&newer).is_err());
}
