import pytest

from helpers import CONFIG_XML, make_wgt
from tizen_poc.discovery import guess_year, parse_samsung_info, subnet_hosts
from tizen_poc.wgt import WgtError, make_test_wgt, parse_config, read_wgt

SAMSUNG_V2 = {
    "device": {
        "OS": "Tizen",
        "developerIP": "192.168.0.20",
        "developerMode": "1",
        "duid": "uuid:1f2e3d4c-0000-1111-2222-333344445555",
        "firmwareVersion": "Unknown",
        "model": "18_KANTM2_QTV",
        "modelName": "QN55Q6FNA",
        "name": "[TV] Samsung Q6 Series (55)",
    },
    "name": "[TV] Samsung Q6 Series (55)",
    "type": "Samsung SmartTV",
    "version": "2.0.25",
}


def test_parse_config():
    info = parse_config(CONFIG_XML)
    assert (info.package_id, info.app_id, info.version) == ("AbCdEf1234", "AbCdEf1234.Player", "1.2.3")
    assert info.required_version == "2.4"
    assert info.name == "Player"


def test_read_wgt_detects_signatures(tmp_path):
    assert not read_wgt(str(make_wgt(tmp_path / "a.wgt"))).has_author_signature
    signed = read_wgt(str(make_wgt(tmp_path / "b.wgt", signed=True)))
    assert signed.has_author_signature and signed.has_distributor_signature


@pytest.mark.parametrize("bad", ["AbC; reboot", "a b", "x$(id)", ""])
def test_ids_that_could_inject_shell_are_rejected(bad):
    xml = CONFIG_XML.replace(b'package="AbCdEf1234"', f'package="{bad}"'.encode())
    with pytest.raises(WgtError):
        parse_config(xml)


def test_make_test_wgt(tmp_path):
    import zipfile

    info = make_test_wgt(str(tmp_path / "teste.wgt"))
    assert len(info.package_id) == 10 and info.package_id.isalnum()
    assert info.app_id == f"{info.package_id}.InstaladorTeste"
    assert not info.has_author_signature
    with zipfile.ZipFile(tmp_path / "teste.wgt") as zf:
        assert zf.read("icon.png").startswith(b"\x89PNG")
        assert b'<tizen:profile name="tv-samsung"/>' in zf.read("config.xml")


def test_make_test_wgt_profile_and_unpacked(tmp_path):
    info = make_test_wgt(str(tmp_path / "t.wgt"), profile="tv", unpacked=str(tmp_path / "src"))
    config = (tmp_path / "src" / "config.xml").read_bytes()
    assert b'<tizen:profile name="tv"/>' in config
    assert info.package_id.encode() in config
    assert (tmp_path / "src" / "icon.png").is_file()


def test_not_a_zip(tmp_path):
    path = tmp_path / "x.wgt"
    path.write_bytes(b"nope")
    with pytest.raises(WgtError):
        read_wgt(str(path))


def test_parse_samsung_info():
    tv = parse_samsung_info("192.168.0.50", SAMSUNG_V2)
    assert tv.model_name == "QN55Q6FNA"
    assert tv.year == 2018
    assert tv.factory_tizen == "4.0"
    assert tv.developer_mode is True
    assert tv.developer_ip == "192.168.0.20"
    assert tv.duid.startswith("uuid:")


def test_parse_samsung_info_minimal():
    tv = parse_samsung_info("10.0.0.2", {"device": {}})
    assert tv.developer_mode is None
    assert tv.year is None


@pytest.mark.parametrize("model, year", [("16_JAZZM_UHD", 2016), ("22_PONTUSM_FHD", 2022), ("KANTM", None), ("", None)])
def test_guess_year(model, year):
    assert guess_year(model) == year


def test_subnet_hosts():
    hosts = subnet_hosts("192.168.0.37/24")
    assert len(hosts) == 254
    assert hosts[0] == "192.168.0.1"
    with pytest.raises(ValueError):
        subnet_hosts("10.0.0.0/8")
