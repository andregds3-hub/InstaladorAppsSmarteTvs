from app.compat import describe, guess_year, is_compatible, parse_version
from app.models import Compatibility
from app.security import RateLimiter, hash_password, new_access_code, normalize_code, verify_password


def test_password_hash_and_72_byte_truncation():
    hashed = hash_password("correta")
    assert verify_password("correta", hashed)
    assert not verify_password("errada", hashed)
    long = "á" * 40  # 80 bytes em UTF-8
    hashed_long = hash_password(long)
    assert verify_password(long, hashed_long)
    assert verify_password(long.encode()[:72].decode("utf-8", "ignore") + "á" * 10, hashed_long)
    assert not verify_password("x", "não-é-hash")


def test_access_code_format():
    code = new_access_code()
    assert len(code) == 14 and code.count("-") == 2
    assert normalize_code(code.lower()) == code.replace("-", "")


def test_rate_limiter():
    limiter = RateLimiter()
    assert all(limiter.allow("k", 3) for _ in range(3))
    assert not limiter.allow("k", 3)
    assert limiter.allow("other", 3)


def test_versions_and_years():
    assert parse_version("Tizen 5.5") == (5, 5)
    assert parse_version("") is None
    assert guess_year("16_JAZZM_UHD") == 2016
    assert guess_year("QN55Q6FNA") is None


def rule(**kw) -> Compatibility:
    return Compatibility(platform=kw.pop("platform", "tizen"), **kw)


def test_compatibility_rules():
    assert is_compatible([], "tizen", None, None)
    tizen_2016_plus = [rule(min_year=2016, min_os="2.4")]
    assert is_compatible(tizen_2016_plus, "tizen", 2018, "4.0")
    assert not is_compatible(tizen_2016_plus, "tizen", 2015, "2.3")
    assert not is_compatible(tizen_2016_plus, "tizen", None, "2.3")
    assert is_compatible(tizen_2016_plus, "tizen", None, None)  # sem dados: o cliente confirma no app
    assert not is_compatible(tizen_2016_plus, "android_tv", 2020, None)
    up_to_6_5 = [rule(max_os="6.5")]
    assert is_compatible(up_to_6_5, "tizen", None, "6.5.1")
    assert not is_compatible(up_to_6_5, "tizen", None, "7.0")
    either = [rule(max_year=2017), rule(min_year=2020)]
    assert is_compatible(either, "tizen", 2016, None) and is_compatible(either, "tizen", 2021, None)
    assert not is_compatible(either, "tizen", 2018, None)
    assert describe(rule(min_year=2016, min_os="2.4")) == "TVs 2016–…, Tizen 2.4–…"
