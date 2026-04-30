import React, {useEffect, useRef, useState} from 'react';
import {
  Alert, DeviceEventEmitter, NativeModules, PermissionsAndroid,
  ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View,
} from 'react-native';

const {SipPhone} = NativeModules;

const DEFAULT_HOST = '103.82.193.58';
const DEFAULT_PORT = 5060;
const DEFAULT_USER = '1001';
const DEFAULT_PASS = 'AppVoip-2026!';

const DIAL_KEYS = [
  ['1','2','3'],
  ['4','5','6'],
  ['7','8','9'],
  ['*','0','#'],
];

export default function App() {
  const [tab, setTab] = useState('dial'); // 'dial' | 'messages' | 'log'
  const [regState, setRegState] = useState('Unregistered');
  const [registered, setRegistered] = useState(false);
  const [inCall, setInCall] = useState(false);
  const [callInfo, setCallInfo] = useState('');
  const [incoming, setIncoming] = useState(null); // {from}
  const [dialValue, setDialValue] = useState('');
  const [chatTarget, setChatTarget] = useState('');
  const [chatInput, setChatInput] = useState('');
  const [messages, setMessages] = useState([]); // [{from, text, mine, ts}]
  const [callLog, setCallLog] = useState([]);   // [{type, from, ts}]
  const [log, setLog] = useState([]);

  const addLog = msg => setLog(l => [{ts: new Date().toLocaleTimeString(), msg}, ...l].slice(0, 100));

  // permissions
  useEffect(() => {
    (async () => {
      try {
        await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
          PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
        ]);
      } catch (e) {}
    })();
  }, []);

  // event listeners
  useEffect(() => {
    const subs = [
      DeviceEventEmitter.addListener('SipPhoneRegistration', ev => {
        setRegState(ev.state || 'Unknown');
        const ok = ev.state === 'Ok';
        setRegistered(ok);
        addLog('Registration: ' + ev.state + (ev.message ? ' — ' + ev.message : ''));
      }),
      DeviceEventEmitter.addListener('SipPhoneIncoming', ev => {
        setIncoming({from: ev.from || 'Unknown'});
        addLog('Incoming call from ' + (ev.from || 'unknown'));
        setCallLog(l => [{type: 'in', from: ev.from || '?', ts: new Date().toLocaleTimeString()}, ...l].slice(0, 100));
      }),
      DeviceEventEmitter.addListener('SipPhoneCallState', ev => {
        addLog('Call state: ' + ev.state + (ev.from ? ' from ' + ev.from : ''));
        if (ev.state === 'Connected' || ev.state === 'StreamsRunning') {
          setInCall(true);
          setIncoming(null);
          setCallInfo('In call' + (ev.from ? ' with ' + ev.from : ''));
        } else if (ev.state === 'End' || ev.state === 'Released' || ev.state === 'Error') {
          setInCall(false);
          setIncoming(null);
          setCallInfo('');
        }
      }),
      DeviceEventEmitter.addListener('SipPhoneMessage', ev => {
        const {from, text} = ev;
        setMessages(m => [...m, {from, text, mine: false, ts: new Date().toLocaleTimeString()}]);
        if (tab !== 'messages') addLog('Message from ' + from + ': ' + text);
      }),
      DeviceEventEmitter.addListener('SipPhoneMessageSent', ev => {
        setMessages(m => [...m, {from: 'Me→' + ev.to, text: ev.text, mine: true, ts: new Date().toLocaleTimeString()}]);
      }),
    ];
    return () => subs.forEach(s => s.remove());
  }, [tab]);

  const onRegister = async () => {
    try {
      addLog('Registering...');
      const msg = await SipPhone.register(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_USER, DEFAULT_PASS);
      addLog(msg);
    } catch (e) { addLog('Register error: ' + e.message); Alert.alert('Error', e.message); }
  };

  const onUnregister = async () => {
    try {
      await SipPhone.unregister();
      setRegistered(false); setRegState('Unregistered'); setInCall(false); setIncoming(null);
      addLog('Unregistered');
    } catch (e) { addLog('Unregister error: ' + e.message); }
  };

  const onCall = async () => {
    if (!dialValue.trim()) { Alert.alert('Enter number'); return; }
    try {
      addLog('Calling ' + dialValue.trim() + '...');
      setCallLog(l => [{type: 'out', from: dialValue.trim(), ts: new Date().toLocaleTimeString()}, ...l].slice(0, 100));
      const msg = await SipPhone.call(dialValue.trim());
      addLog(msg);
    } catch (e) { addLog('Call error: ' + e.message); Alert.alert('Error', e.message); }
  };

  const onAnswer = async () => {
    try {
      await SipPhone.answer();
      setInCall(true); setIncoming(null);
    } catch (e) { addLog('Answer error: ' + e.message); }
  };

  const onHangup = async () => {
    try {
      await SipPhone.hangup();
      setInCall(false); setIncoming(null); setCallInfo('');
    } catch (e) { addLog('Hangup error: ' + e.message); }
  };

  const onSendMessage = async () => {
    if (!chatTarget.trim()) { Alert.alert('Enter recipient'); return; }
    if (!chatInput.trim()) { Alert.alert('Enter message'); return; }
    try {
      await SipPhone.sendMessage(chatTarget.trim(), chatInput.trim());
      setChatInput('');
    } catch (e) { addLog('Message error: ' + e.message); Alert.alert('Error', e.message); }
  };

  // ------ Render ------

  const regColor = registered ? '#22c55e' : regState.includes('Progress') ? '#f59e0b' : '#ef4444';

  return (
    <View style={s.root}>
      {/* Header */}
      <View style={s.header}>
        <Text style={s.appTitle}>SIP Phone 1001</Text>
        <View style={s.regRow}>
          <View style={[s.dot, {backgroundColor: regColor}]} />
          <Text style={[s.regText, {color: regColor}]}>{regState}</Text>
        </View>
        <View style={s.regBtns}>
          {!registered
            ? <TouchableOpacity style={s.btnSmall} onPress={onRegister}><Text style={s.btnSmallTxt}>Register</Text></TouchableOpacity>
            : <TouchableOpacity style={[s.btnSmall, s.btnSmallRed]} onPress={onUnregister}><Text style={s.btnSmallTxt}>Unregister</Text></TouchableOpacity>
          }
        </View>
      </View>

      {/* Incoming call banner */}
      {incoming && (
        <View style={s.incomingBar}>
          <Text style={s.incomingTxt}>Incoming: {incoming.from}</Text>
          <TouchableOpacity style={s.btnGreen} onPress={onAnswer}><Text style={s.btnSmallTxt}>Answer</Text></TouchableOpacity>
          <TouchableOpacity style={s.btnRed} onPress={onHangup}><Text style={s.btnSmallTxt}>Reject</Text></TouchableOpacity>
        </View>
      )}
      {inCall && (
        <View style={s.inCallBar}>
          <Text style={s.inCallTxt}>{callInfo}</Text>
          <TouchableOpacity style={s.btnRed} onPress={onHangup}><Text style={s.btnSmallTxt}>Hang Up</Text></TouchableOpacity>
        </View>
      )}

      {/* Tabs */}
      <View style={s.tabs}>
        {['dial','messages','log'].map(t => (
          <TouchableOpacity key={t} style={[s.tab, tab === t && s.tabActive]} onPress={() => setTab(t)}>
            <Text style={[s.tabTxt, tab === t && s.tabTxtActive]}>
              {t === 'dial' ? 'Dial' : t === 'messages' ? 'Messages' : 'Log'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* Tab content */}
      {tab === 'dial' && (
        <View style={s.dialPane}>
          <TextInput
            style={s.dialDisplay}
            value={dialValue}
            onChangeText={setDialValue}
            placeholder="Number or extension"
            placeholderTextColor="#555"
            keyboardType="phone-pad"
          />
          <View style={s.keypad}>
            {DIAL_KEYS.map((row, ri) => (
              <View key={ri} style={s.keyRow}>
                {row.map(k => (
                  <TouchableOpacity key={k} style={s.key} onPress={() => setDialValue(v => v + k)}>
                    <Text style={s.keyTxt}>{k}</Text>
                  </TouchableOpacity>
                ))}
              </View>
            ))}
          </View>
          <View style={s.callRow}>
            <TouchableOpacity style={s.btnDel} onPress={() => setDialValue(v => v.slice(0, -1))}>
              <Text style={s.btnSmallTxt}>⌫</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[s.btnCall, !registered && s.btnDisabled]} onPress={onCall} disabled={!registered}>
              <Text style={s.btnCallTxt}>Call</Text>
            </TouchableOpacity>
          </View>

          {/* Call log */}
          {callLog.length > 0 && (
            <ScrollView style={s.callLogList}>
              {callLog.map((entry, i) => (
                <TouchableOpacity key={i} onPress={() => setDialValue(entry.from)}>
                  <View style={s.callLogItem}>
                    <Text style={[s.callLogType, entry.type === 'in' ? s.callIn : s.callOut]}>
                      {entry.type === 'in' ? '↙' : '↗'}
                    </Text>
                    <Text style={s.callLogFrom}>{entry.from}</Text>
                    <Text style={s.callLogTs}>{entry.ts}</Text>
                  </View>
                </TouchableOpacity>
              ))}
            </ScrollView>
          )}
        </View>
      )}

      {tab === 'messages' && (
        <View style={s.msgPane}>
          <TextInput
            style={s.msgInput}
            value={chatTarget}
            onChangeText={setChatTarget}
            placeholder="To (extension)"
            placeholderTextColor="#555"
          />
          <ScrollView style={s.msgList}>
            {messages.map((m, i) => (
              <View key={i} style={[s.bubble, m.mine ? s.bubbleMine : s.bubblePeer]}>
                <Text style={s.bubbleFrom}>{m.from}</Text>
                <Text style={s.bubbleText}>{m.text}</Text>
                <Text style={s.bubbleTs}>{m.ts}</Text>
              </View>
            ))}
          </ScrollView>
          <View style={s.msgCompose}>
            <TextInput
              style={s.msgComposeInput}
              value={chatInput}
              onChangeText={setChatInput}
              placeholder="Message..."
              placeholderTextColor="#555"
              multiline
            />
            <TouchableOpacity style={[s.btnSend, !registered && s.btnDisabled]} onPress={onSendMessage} disabled={!registered}>
              <Text style={s.btnSmallTxt}>Send</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}

      {tab === 'log' && (
        <ScrollView style={s.logPane}>
          {log.length === 0 && <Text style={s.logEmpty}>No events yet</Text>}
          {log.map((e, i) => (
            <Text key={i} style={s.logItem}>[{e.ts}] {e.msg}</Text>
          ))}
        </ScrollView>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#0f0f0f'},
  header: {paddingTop: 48, paddingHorizontal: 16, paddingBottom: 12, backgroundColor: '#161616', borderBottomWidth: 1, borderColor: '#2a2a2a'},
  appTitle: {color: '#fff', fontSize: 18, fontWeight: '700', marginBottom: 4},
  regRow: {flexDirection: 'row', alignItems: 'center', marginBottom: 8},
  dot: {width: 8, height: 8, borderRadius: 4, marginRight: 6},
  regText: {fontSize: 13},
  regBtns: {flexDirection: 'row', gap: 8},
  btnSmall: {backgroundColor: '#2563eb', paddingHorizontal: 14, paddingVertical: 6, borderRadius: 6},
  btnSmallRed: {backgroundColor: '#7f1d1d'},
  btnSmallTxt: {color: '#fff', fontSize: 13, fontWeight: '600'},
  incomingBar: {flexDirection: 'row', alignItems: 'center', backgroundColor: '#14532d', padding: 10, gap: 10},
  incomingTxt: {color: '#fff', flex: 1, fontWeight: '600'},
  btnGreen: {backgroundColor: '#15803d', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 6},
  btnRed: {backgroundColor: '#b91c1c', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 6},
  inCallBar: {flexDirection: 'row', alignItems: 'center', backgroundColor: '#1e3a5f', padding: 10, gap: 10},
  inCallTxt: {color: '#93c5fd', flex: 1, fontWeight: '600'},
  tabs: {flexDirection: 'row', borderBottomWidth: 1, borderColor: '#2a2a2a'},
  tab: {flex: 1, paddingVertical: 10, alignItems: 'center'},
  tabActive: {borderBottomWidth: 2, borderColor: '#2563eb'},
  tabTxt: {color: '#555', fontSize: 13, fontWeight: '600'},
  tabTxtActive: {color: '#2563eb'},
  // Dial
  dialPane: {flex: 1, padding: 16},
  dialDisplay: {backgroundColor: '#1e1e1e', color: '#fff', fontSize: 22, padding: 14, borderRadius: 10, textAlign: 'center', marginBottom: 12},
  keypad: {marginBottom: 12},
  keyRow: {flexDirection: 'row', justifyContent: 'space-around', marginBottom: 8},
  key: {width: 72, height: 56, backgroundColor: '#1e1e1e', borderRadius: 10, alignItems: 'center', justifyContent: 'center'},
  keyTxt: {color: '#fff', fontSize: 22, fontWeight: '500'},
  callRow: {flexDirection: 'row', gap: 10, alignItems: 'center', marginBottom: 12},
  btnDel: {backgroundColor: '#374151', paddingHorizontal: 18, paddingVertical: 14, borderRadius: 10},
  btnCall: {flex: 1, backgroundColor: '#15803d', paddingVertical: 14, borderRadius: 10, alignItems: 'center'},
  btnCallTxt: {color: '#fff', fontSize: 18, fontWeight: '700'},
  btnDisabled: {opacity: 0.4},
  callLogList: {flex: 1, marginTop: 4},
  callLogItem: {flexDirection: 'row', alignItems: 'center', paddingVertical: 8, borderBottomWidth: 1, borderColor: '#1e1e1e'},
  callLogType: {fontSize: 16, marginRight: 8, width: 20, textAlign: 'center'},
  callIn: {color: '#22c55e'},
  callOut: {color: '#3b82f6'},
  callLogFrom: {color: '#fff', flex: 1},
  callLogTs: {color: '#555', fontSize: 11},
  // Messages
  msgPane: {flex: 1, padding: 12},
  msgInput: {backgroundColor: '#1e1e1e', color: '#fff', padding: 10, borderRadius: 8, marginBottom: 8},
  msgList: {flex: 1, marginBottom: 8},
  bubble: {maxWidth: '80%', padding: 10, borderRadius: 10, marginVertical: 4},
  bubbleMine: {backgroundColor: '#1e40af', alignSelf: 'flex-end'},
  bubblePeer: {backgroundColor: '#1f2937', alignSelf: 'flex-start'},
  bubbleFrom: {color: '#93c5fd', fontSize: 11, marginBottom: 2},
  bubbleText: {color: '#fff', fontSize: 14},
  bubbleTs: {color: '#6b7280', fontSize: 10, marginTop: 4, textAlign: 'right'},
  msgCompose: {flexDirection: 'row', gap: 8, alignItems: 'flex-end'},
  msgComposeInput: {flex: 1, backgroundColor: '#1e1e1e', color: '#fff', padding: 10, borderRadius: 8, maxHeight: 80},
  btnSend: {backgroundColor: '#2563eb', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 8},
  // Log
  logPane: {flex: 1, padding: 12},
  logEmpty: {color: '#555', textAlign: 'center', marginTop: 40},
  logItem: {color: '#6b7280', fontSize: 11, marginBottom: 4, fontFamily: 'monospace'},
});
