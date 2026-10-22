//! Servo profile files the bridge manages before Servo starts.
//!
//! Servo 0.6.0 keeps proxy sign-ins (answers to a 407, which any server can send: Servo does
//! not check that a proxy is configured) in its HTTP auth cache with the password in plain
//! text, reads `auth_cache.json` back when it starts, and sends the saved answer again without
//! asking. Patch 0004 writes that file with the cookies. Deleting it before Servo starts keeps
//! such a sign-in to one run of the app; the running session keeps its in-memory cache, so a
//! proxy sign-in still works until the app stops. The file is still written while the app
//! runs, until the planned patch change (docs/ENGINE_SPIKE.md section 13).

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use std::io;
use std::path::Path;

/// Servo's auth cache, and the temporary copy patch 0004 writes before renaming it.
const SAVED_SIGN_INS: [&str; 2] = ["auth_cache.json", "auth_cache.json.tmp"];

/// Deletes the sign-ins an earlier run saved in `profile_dir`; returns how many files it
/// deleted. Cookies, HSTS and site storage are left alone.
pub(crate) fn forget_saved_sign_ins(profile_dir: &Path) -> io::Result<usize> {
    let mut deleted = 0;
    for name in SAVED_SIGN_INS {
        match std::fs::remove_file(profile_dir.join(name)) {
            Ok(()) => deleted += 1,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {},
            Err(error) => return Err(error),
        }
    }
    Ok(deleted)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;

    use super::*;

    fn profile(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("combray-profile-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn saved_sign_ins_are_deleted_and_other_profile_files_kept() {
        let dir = profile("sign-ins");
        for name in [
            "auth_cache.json",
            "auth_cache.json.tmp",
            "cookie_jar.json",
            "hsts_list.json",
        ] {
            fs::write(dir.join(name), "{}").unwrap();
        }

        assert_eq!(forget_saved_sign_ins(&dir).unwrap(), 2);

        assert!(!dir.join("auth_cache.json").exists());
        assert!(!dir.join("auth_cache.json.tmp").exists());
        assert!(dir.join("cookie_jar.json").exists());
        assert!(dir.join("hsts_list.json").exists());
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_profile_without_saved_sign_ins_is_fine() {
        let dir = profile("empty");
        assert_eq!(forget_saved_sign_ins(&dir).unwrap(), 0);
        assert_eq!(
            forget_saved_sign_ins(&dir.join("missing")).unwrap(),
            0,
            "a profile directory that does not exist yet"
        );
        fs::remove_dir_all(&dir).unwrap();
    }
}
