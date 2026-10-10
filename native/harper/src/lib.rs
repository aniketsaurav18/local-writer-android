//! Offline Harper JNI adapter. Offsets crossing the boundary are UTF-16 units.
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, OnceLock};

use harper_core::linting::{LintGroup, LintKind, Suggestion};
use harper_core::spell::{FstDictionary, MergedDictionary, MutableDictionary};
use harper_core::{Dialect, DictWordMetadata, Document};
use jni::JNIEnv;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{jboolean, jstring};
use serde::Serialize;

struct Checker {
    dialect: Dialect,
    words: Vec<String>,
    dictionary: Arc<MergedDictionary>,
    rules: LintGroup,
}

static CHECKER: OnceLock<Mutex<Option<Checker>>> = OnceLock::new();

impl Checker {
    fn new(dialect: Dialect, words: Vec<String>) -> Self {
        let mut personal = MutableDictionary::new();
        for word in &words {
            personal.append_word_str(word, DictWordMetadata::default());
        }
        let mut dictionary = MergedDictionary::new();
        dictionary.add_dictionary(FstDictionary::curated());
        dictionary.add_dictionary(Arc::new(personal));
        let dictionary = Arc::new(dictionary);
        let rules = LintGroup::new_curated(dictionary.clone(), dialect);
        Self {
            dialect,
            words,
            dictionary,
            rules,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Finding {
    begin: usize,
    end: usize,
    replacement: String,
    alternatives: Vec<String>,
    category: String,
    explanation: String,
    rule_id: String,
    actionable: bool,
    priority: u8,
    style: bool,
}

fn dialect_of(name: &str) -> Result<Dialect, String> {
    match name.to_ascii_lowercase().as_str() {
        "en-us" | "us" | "american" => Ok(Dialect::American),
        "en-gb" | "en-uk" | "gb" | "british" => Ok(Dialect::British),
        "en-ca" | "ca" | "canadian" => Ok(Dialect::Canadian),
        "en-au" | "au" | "australian" => Ok(Dialect::Australian),
        "en-in" | "in" | "indian" => Ok(Dialect::Indian),
        _ => Err(format!("Unsupported English dialect: {name}")),
    }
}

fn style_kind(kind: LintKind) -> bool {
    matches!(
        kind,
        LintKind::Style | LintKind::Enhancement | LintKind::Readability | LintKind::Redundancy
    )
}

// Suggestions of a different operation cannot share the chosen edit range.
fn replacement_for(suggestion: &Suggestion, insertion: bool) -> Option<String> {
    match suggestion {
        Suggestion::InsertAfter(chars) if insertion => Some(chars.iter().collect()),
        Suggestion::ReplaceWith(chars) if !insertion => Some(chars.iter().collect()),
        Suggestion::Remove if !insertion => Some(String::new()),
        _ => None,
    }
}

fn check(
    text: &str,
    dialect: Dialect,
    mut words: Vec<String>,
    style: bool,
) -> Result<String, String> {
    words.retain(|w| !w.is_empty());
    words.sort_unstable();
    words.dedup();
    let mutex = CHECKER.get_or_init(|| Mutex::new(None));
    let mut state = match mutex.lock() {
        Ok(state) => state,
        Err(poisoned) => {
            // A previous panic was already reported to Kotlin. Discard that
            // checker's partially updated caches before accepting another call.
            let mut state = poisoned.into_inner();
            *state = None;
            mutex.clear_poison();
            state
        }
    };
    if state
        .as_ref()
        .is_none_or(|c| c.dialect != dialect || c.words != words)
    {
        *state = Some(Checker::new(dialect, words));
    }
    let checker = state
        .as_mut()
        .ok_or("Harper checker could not be initialized")?;
    let document = Document::new_plain_english(text, &checker.dictionary);
    let mut utf16 = Vec::with_capacity(text.chars().count() + 1);
    utf16.push(0);
    for ch in text.chars() {
        utf16.push(utf16.last().copied().unwrap_or(0) + ch.len_utf16());
    }
    let mut findings = Vec::new();
    // Unlike Linter::lint, this API preserves the actual upstream public rule IDs.
    for (rule_id, lints) in checker.rules.organized_lints(&document) {
        for lint in lints {
            let is_style = style_kind(lint.lint_kind);
            if is_style && !style {
                continue;
            }
            let (Some(&start), Some(&end)) = (utf16.get(lint.span.start), utf16.get(lint.span.end))
            else {
                return Err("Harper returned an invalid character span".into());
            };
            if start > end {
                return Err("Harper returned a reversed span".into());
            }
            let insertion = matches!(lint.suggestions.first(), Some(Suggestion::InsertAfter(_)));
            let mut alternatives = Vec::new();
            for suggestion in &lint.suggestions {
                if let Some(value) = replacement_for(suggestion, insertion) {
                    if !alternatives.contains(&value) {
                        alternatives.push(value);
                    }
                }
            }
            let original = if insertion {
                String::new()
            } else {
                lint.get_str(document.get_source())
            };
            alternatives.retain(|replacement| replacement != &original);
            let actionable = !alternatives.is_empty();
            // A warning keeps its visible original span even when a no-op insertion
            // was the only proposed suggestion.
            let begin = if insertion && actionable { end } else { start };
            let original = if actionable {
                original
            } else {
                lint.get_str(document.get_source())
            };
            findings.push(Finding {
                begin,
                end,
                replacement: alternatives.first().cloned().unwrap_or(original),
                alternatives,
                category: lint.lint_kind.to_string_key(),
                explanation: lint.message,
                rule_id: rule_id.clone(),
                actionable,
                priority: lint.priority,
                style: is_style,
            });
        }
    }
    findings.sort_by(|a, b| {
        (a.begin, a.end, a.priority, &a.rule_id).cmp(&(b.begin, b.end, b.priority, &b.rule_id))
    });
    serde_json::to_string(&findings).map_err(|error| error.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_localwriter_HarperEngine_lint(
    mut env: JNIEnv,
    _this: JObject,
    text: JString,
    dialect: JString,
    personal_dictionary: JObjectArray,
    style: jboolean,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<_, String> {
        let text: String = env.get_string(&text).map_err(|e| e.to_string())?.into();
        let dialect: String = env.get_string(&dialect).map_err(|e| e.to_string())?.into();
        let count = env
            .get_array_length(&personal_dictionary)
            .map_err(|e| e.to_string())?;
        let mut words = Vec::with_capacity(count as usize);
        for i in 0..count {
            let word = JString::from(
                env.get_object_array_element(&personal_dictionary, i)
                    .map_err(|e| e.to_string())?,
            );
            let value: String = env.get_string(&word).map_err(|e| e.to_string())?.into();
            words.push(value);
            env.delete_local_ref(word).map_err(|e| e.to_string())?;
        }
        let output = check(&text, dialect_of(&dialect)?, words, style != 0)?;
        env.new_string(output)
            .map(|s| s.into_raw())
            .map_err(|e| e.to_string())
    }));
    match result {
        Ok(Ok(output)) => output,
        error => {
            let message = match error {
                Ok(Err(message)) => message,
                _ => "Harper encountered an internal error".into(),
            };
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
            std::ptr::null_mut()
        }
    }
}
