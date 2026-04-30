import React, {memo, useEffect, useState} from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  StyleSheet, Alert, NativeModules, ScrollView, DeviceEventEmitter
} from 'react-native';

const {SipBridge, SipPhone} = NativeModules;

const Field = memo(function Field({
  label,
  value,
  onChangeText,
  secure,
  keyboardType,
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        blurOnSubmit={false}
        keyboardType={keyboardType || 'default'}
        placeholderTextColor="#555"
        placeholder={label}
      />
    </View>
  );
});

export default function App() {
  const [tab, setTab] = useState('softphone');

  // Existing GSM <-> SIP gateway config
  const [host, setHost] = useState('103.82.193.58');
  const [port, setPort] = useState('5060');
  const [username, setUsername] = useState('3001');
  const [password, setPassword] = useState('Gateway3001');
  const [bridgeExtension, setBridgeExtension] = useState('1001');
  const [answerRings, setAnswerRings] = useState('1');
  const [status, setStatus] = useState('Gateway not configured');
  const [running, setRunning] = useState(false);

  // Softphone (MicroSIP-like) for extension 1001
  const [sipHost, setSipHost] = useState('103.82.193.58');
  const [sipPort, setSipPort] = useState('5060');
  const [sipUser, setSipUser] = useState('1001');
  const [sipPass, setSipPass] = useState('AppVoip-2026!');
  const [regState, setRegState] = useState('Not registered');
  const [dialTarget, setDialTarget] = useState('3001');
  const [chatTarget, setChatTarget] = useState('3001');
  const [chatText, setChatText] = useState('');
  const [incomingFrom, setIncomingFrom] = useState('');
  const [inCall, setInCall] = useState(false);
  const [messages, setMessages] = useState([]);

  const addLog = (line) => {
    const stamp = new Date().toLocaleTimeString();
    setMessages(prev => [`[${stamp}] ${line}`, ...prev].slice(0, 60));
  };

  useEffect(() => {
    const subs = [
      DeviceEventEmitter.addListener('SipPhoneRegistration', e => {
        const msg = `${e?.state || 'Unknown'} ${e?.message ? '- ' + e.message : ''}`.trim();
        setRegState(msg);
        addLog(`REG: ${msg}`);
      }),
      DeviceEventEmitter.addListener('SipPhoneIncomingCall', e => {
        const from = e?.from || 'Unknown';
        setIncomingFrom(from);
        addLog(`Incoming call from ${from}`);
      }),
      DeviceEventEmitter.addListener('SipPhoneCall', e => {
        const st = e?.state || 'Unknown';
        addLog(`Call state: ${st}`);
        if (st === 'Connected' || st === 'StreamsRunning') {
          setInCall(true);
          setIncomingFrom('');
        }
        if (st === 'End' || st === 'Released' || st === 'Error') {
          setInCall(false);
          setIncomingFrom('');
        }
      }),
      DeviceEventEmitter.addListener('SipPhoneMessage', e => {
        addLog(`MSG from ${e?.from || 'Unknown'}: ${e?.text || ''}`);
      }),
      DeviceEventEmitter.addListener('SipPhoneMessageSent', e => {
        addLog(`MSG to ${e?.to || 'Unknown'}: ${e?.text || ''}`);
      }),
    ];

    return () => subs.forEach(s => s.remove());
  }, []);

  const saveAndStart = async () => {
    try {
      const result = await SipBridge.saveConfig({
        host: host.trim(),
        port: parseInt(port, 10) || 5060,
        username: username.trim(),
        password: password.trim(),
        bridgeExtension: bridgeExtension.trim(),
        answerRings: parseInt(answerRings, 10) || 1,
      });
      setStatus('Running');
      setRunning(true);
      Alert.alert('Success', result);
    } catch (e) {
      setStatus('Error: ' + e.message);
      Alert.alert('Error', e.message);
    }
  };

  const stopGateway = async () => {
    try {
      const result = await SipBridge.stopService();
      setStatus('Stopped');
      setRunning(false);
      Alert.alert('Stopped', result);
    } catch (e) {
      Alert.alert('Error', e.message);
    }
  };

  const register1001 = async () => {
    if (!SipPhone) {
      Alert.alert('Error', 'SipPhone native module not found. Rebuild APK first.');
      return;
    }
    try {
      await SipPhone.registerAccount(
        sipHost.trim(),
        parseInt(sipPort, 10) || 5060,
        sipUser.trim(),
        sipPass.trim(),
      );
      addLog(`Register requested for ${sipUser.trim()}@${sipHost.trim()}`);
    } catch (e) {
      Alert.alert('Register failed', e.message);
      addLog(`Register failed: ${e.message}`);
    }
  };

  const unregister1001 = async () => {
    if (!SipPhone) return;
    try {
      await SipPhone.unregister();
      setRegState('Not registered');
      setInCall(false);
      setIncomingFrom('');
      addLog('Unregistered');
    } catch (e) {
      Alert.alert('Error', e.message);
    }
  };

  const makeSipCall = async () => {
    if (!SipPhone) return;
    try {
      await SipPhone.makeCall(dialTarget.trim());
      addLog(`Calling ${dialTarget.trim()}`);
    } catch (e) {
      Alert.alert('Call failed', e.message);
      addLog(`Call failed: ${e.message}`);
    }
  };

  const answerSipCall = async () => {
    if (!SipPhone) return;
    try {
      await SipPhone.answerCall();
      addLog('Answered incoming call');
    } catch (e) {
      Alert.alert('Answer failed', e.message);
    }
  };

  const hangupSipCall = async () => {
    if (!SipPhone) return;
    try {
      await SipPhone.hangup();
      setInCall(false);
      setIncomingFrom('');
      addLog('Hangup');
    } catch (e) {
      Alert.alert('Hangup failed', e.message);
    }
  };

  const sendSipMessage = async () => {
    if (!SipPhone) return;
    try {
      await SipPhone.sendMessage(chatTarget.trim(), chatText.trim());
      setChatText('');
    } catch (e) {
      Alert.alert('Send failed', e.message);
      addLog(`Message failed: ${e.message}`);
    }
  };

  return (
    <ScrollView
      style={styles.container}
      keyboardShouldPersistTaps="always"
      keyboardDismissMode="none"
    >
      <Text style={styles.title}>SIP Communication Suite</Text>

      <View style={styles.tabRow}>
        <TouchableOpacity
          style={[styles.tabBtn, tab === 'softphone' && styles.tabBtnActive]}
          onPress={() => setTab('softphone')}
        >
          <Text style={styles.tabText}>Softphone 1001</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tabBtn, tab === 'gateway' && styles.tabBtnActive]}
          onPress={() => setTab('gateway')}
        >
          <Text style={styles.tabText}>Gateway 3001</Text>
        </TouchableOpacity>
      </View>

      {tab === 'softphone' ? (
        <View>
          <View style={styles.statusBar}>
            <Text style={styles.statusText}>SIP 1001: {regState}</Text>
            <Text style={styles.statusText}>Call: {inCall ? 'In call' : 'Idle'}</Text>
            {incomingFrom ? <Text style={styles.statusWarn}>Incoming from: {incomingFrom}</Text> : null}
          </View>

          <Text style={styles.section}>Account 1001</Text>
          <Field label="SIP Server" value={sipHost} onChangeText={setSipHost} />
          <Field label="Port" value={sipPort} onChangeText={setSipPort} keyboardType="numeric" />
          <Field label="Username" value={sipUser} onChangeText={setSipUser} />
          <Field label="Password" value={sipPass} onChangeText={setSipPass} secure />

          <View style={styles.row}>
            <TouchableOpacity style={[styles.btn, styles.btnHalf]} onPress={register1001}>
              <Text style={styles.btnText}>Register</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btnStop, styles.btnHalf]} onPress={unregister1001}>
              <Text style={styles.btnText}>Unregister</Text>
            </TouchableOpacity>
          </View>

          <Text style={styles.section}>Voice Call</Text>
          <Field
            label="Dial extension/number"
            value={dialTarget}
            onChangeText={setDialTarget}
            keyboardType="numbers-and-punctuation"
          />
          <View style={styles.row}>
            <TouchableOpacity style={[styles.btn, styles.btnThird]} onPress={makeSipCall}>
              <Text style={styles.btnText}>Call</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.btn, styles.btnThird, {backgroundColor: '#0f766e'}]}
              onPress={answerSipCall}
            >
              <Text style={styles.btnText}>Answer</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btnStop, styles.btnThird]} onPress={hangupSipCall}>
              <Text style={styles.btnText}>Hangup</Text>
            </TouchableOpacity>
          </View>

          <Text style={styles.section}>SIP Message</Text>
          <Field label="Send to (extension)" value={chatTarget} onChangeText={setChatTarget} />
          <View style={styles.field}>
            <Text style={styles.label}>Message text</Text>
            <TextInput
              style={[styles.input, styles.inputMulti]}
              value={chatText}
              onChangeText={setChatText}
              multiline
              placeholder="Type your SIP message"
              placeholderTextColor="#555"
            />
          </View>
          <TouchableOpacity style={styles.btn} onPress={sendSipMessage}>
            <Text style={styles.btnText}>Send Message</Text>
          </TouchableOpacity>

          <View style={styles.logBox}>
            <Text style={styles.howtoTitle}>Recent Events</Text>
            {messages.length === 0 ? (
              <Text style={styles.howtoItem}>No events yet</Text>
            ) : (
              messages.map((line, idx) => (
                <Text key={idx} style={styles.howtoItem}>{line}</Text>
              ))
            )}
          </View>
        </View>
      ) : (
        <View>
          <View style={styles.statusBar}>
            <Text style={styles.statusText}>Status: {status}</Text>
          </View>
          <Text style={styles.section}>FreePBX Settings</Text>
          <Field
            label="FreePBX IP"
            value={host}
            onChangeText={setHost}
            keyboardType="numbers-and-punctuation"
          />
          <Field
            label="SIP Port"
            value={port}
            onChangeText={setPort}
            keyboardType="numeric"
          />
          <Field
            label="SIP Username"
            value={username}
            onChangeText={setUsername}
          />
          <Field
            label="SIP Password"
            value={password}
            onChangeText={setPassword}
            secure
          />
          <Field
            label="Bridge Target (extension or SIP URI)"
            value={bridgeExtension}
            onChangeText={setBridgeExtension}
          />
          <Field
            label="Answer After Rings"
            value={answerRings}
            onChangeText={setAnswerRings}
            keyboardType="numeric"
          />
          <TouchableOpacity style={styles.btn} onPress={saveAndStart}>
            <Text style={styles.btnText}>Save &amp; Start Gateway</Text>
          </TouchableOpacity>
          {running && (
            <TouchableOpacity style={styles.btnStop} onPress={stopGateway}>
              <Text style={styles.btnText}>Stop Service</Text>
            </TouchableOpacity>
          )}
          <View style={styles.howto}>
            <Text style={styles.howtoTitle}>How it works</Text>
            <Text style={styles.howtoItem}>1. Incoming call arrives on SIM</Text>
            <Text style={styles.howtoItem}>2. App auto-answers the GSM call</Text>
            <Text style={styles.howtoItem}>3. Bridges audio to FreePBX via SIP</Text>
            <Text style={styles.howtoItem}>4. FreePBX routes the call normally</Text>
          </View>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#0a0a0a', padding: 20},
  title: {color: '#fff', fontSize: 22, fontWeight: 'bold', marginTop: 50, marginBottom: 6},
  tabRow: {flexDirection: 'row', gap: 10, marginBottom: 16},
  tabBtn: {
    flex: 1,
    backgroundColor: '#1f2937',
    borderWidth: 1,
    borderColor: '#334155',
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  tabBtnActive: {backgroundColor: '#1d4ed8', borderColor: '#1d4ed8'},
  tabText: {color: '#fff', fontWeight: '700', fontSize: 13},
  statusBar: {backgroundColor: '#1a1a2e', padding: 10, borderRadius: 8, marginBottom: 24},
  statusText: {color: '#88aaff', fontSize: 13},
  statusWarn: {color: '#fca5a5', fontSize: 13, marginTop: 6},
  section: {color: '#888', fontSize: 12, textTransform: 'uppercase', letterSpacing: 1, marginBottom: 12},
  field: {marginBottom: 14},
  label: {color: '#aaa', marginBottom: 5, fontSize: 12},
  input: {backgroundColor: '#1e1e1e', color: '#fff', padding: 12, borderRadius: 8, borderWidth: 1, borderColor: '#2a2a2a'},
  inputMulti: {minHeight: 90, textAlignVertical: 'top'},
  row: {flexDirection: 'row', justifyContent: 'space-between', gap: 8, marginBottom: 8},
  btnHalf: {flex: 1},
  btnThird: {flex: 1},
  btn: {backgroundColor: '#2563eb', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 8, marginBottom: 8},
  btnStop: {backgroundColor: '#dc2626', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 4, marginBottom: 24},
  btnText: {color: '#fff', fontSize: 15, fontWeight: '700'},
  howto: {backgroundColor: '#111827', padding: 16, borderRadius: 10, marginBottom: 40},
  logBox: {backgroundColor: '#111827', padding: 16, borderRadius: 10, marginTop: 8, marginBottom: 40},
  howtoTitle: {color: '#fff', fontWeight: '600', marginBottom: 10},
  howtoItem: {color: '#6b7280', marginBottom: 6, fontSize: 13},
});
