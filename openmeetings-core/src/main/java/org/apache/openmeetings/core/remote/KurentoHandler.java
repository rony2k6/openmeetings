/*

 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.core.remote;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.directory.api.util.Strings;
import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.basic.Client.StreamDesc;
import org.apache.openmeetings.db.entity.basic.IWsClient;
import org.apache.openmeetings.db.entity.room.Room;
import org.apache.openmeetings.db.entity.room.Room.Right;
import org.apache.openmeetings.db.manager.IClientManager;
import org.apache.openmeetings.db.util.ws.RoomMessage;
import org.apache.openmeetings.db.util.ws.TextRoomMessage;
import org.kurento.client.Endpoint;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaObject;
import org.kurento.client.MediaPipeline;
import org.kurento.client.ObjectCreatedEvent;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.RecorderEndpoint;
import org.kurento.client.Tag;
import org.kurento.client.Transaction;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

public class KurentoHandler {
	private final static Logger log = LoggerFactory.getLogger(KurentoHandler.class);
	private final static String MODE_TEST = "test";
	private final static String TAG_KUID = "kuid";
	private final static String TAG_MODE = "mode";
	private final static String TAG_ROOM = "roomId";
	private final static String HMAC_SHA1_ALGORITHM = "HmacSHA1";
	public final static String KURENTO_TYPE = "kurento";
	private long checkTimeout = 200; //ms
	private String kurentoWsUrl;
	private String turnUrl;
	private String turnUser;
	private String turnSecret;
	private String turnMode;
	private int turnTtl = 60; //minutes
	private KurentoClient client;
	private String kuid;
	private final Map<Long, KRoom> rooms = new ConcurrentHashMap<>();
	final Map<String, KStream> usersByUid = new ConcurrentHashMap<>();
	final Map<String, KTestStream> testsByUid = new ConcurrentHashMap<>();

	@Autowired
	private IClientManager cm;

	public void init() {
		try {
			// TODO check connection, reconnect, listeners etc.
			client = KurentoClient.create(kurentoWsUrl);
			kuid = UUID.randomUUID().toString(); //FIXME TODO regenerate on re-connect
			client.getServerManager().addObjectCreatedListener(new KWatchDog());
		} catch (Exception e) {
			log.error("Fail to create Kurento client", e);
		}
	}

	public void destroy() {
		if (client != null) {
			for (Entry<Long, KRoom> e : rooms.entrySet()) {
				e.getValue().close();
			}
			rooms.clear();
			for (Entry<String, KTestStream> e : testsByUid.entrySet()) {
				e.getValue().release();
			}
			testsByUid.clear();
			usersByUid.clear();
			client.destroy();
		}
	}

	private static Map<String, String> tagsAsMap(MediaObject pipe) {
		Map<String, String> map = new HashMap<>();
		for (Tag t : pipe.getTags()) {
			map.put(t.getKey(), t.getValue());
		}
		return map;
	}

	private MediaPipeline createTestPipeline() {
		Transaction t = client.beginTransaction();
		MediaPipeline pipe = client.createMediaPipeline(t);
		pipe.addTag(t, TAG_KUID, kuid);
		pipe.addTag(t, TAG_MODE, MODE_TEST);
		pipe.addTag(t, TAG_ROOM, MODE_TEST);
		t.commit();
		return pipe;
	}

	public void onMessage(IWsClient _c, JSONObject msg) {
		if (client == null) {
			sendError(_c, "Multimedia server is inaccessible");
			return;
		}
		final String cmdId = msg.getString("id");
		if (MODE_TEST.equals(msg.optString(TAG_MODE))) {
			KTestStream user = getTestByUid(_c.getUid());
			switch (cmdId) {
				case "wannaRecord":
					WebSocketHelper.sendClient(_c, newTestKurentoMsg()
							.put("id", "canRecord")
							.put("iceServers", getTurnServers(true))
							);
					break;
				case "record":
				{
					user = new KTestStream(_c, msg, createTestPipeline());
					testsByUid.put(_c.getUid(), user);
				}
					break;
				case "iceCandidate":
				{
					JSONObject candidate = msg.getJSONObject("candidate");
					if (user != null) {
						IceCandidate cand = new IceCandidate(candidate.getString("candidate"),
								candidate.getString("sdpMid"), candidate.getInt("sdpMLineIndex"));
						user.addCandidate(cand);
					}
				}
					break;
				case "wannaPlay":
					WebSocketHelper.sendClient(_c, newTestKurentoMsg()
							.put("id", "canPlay")
							.put("iceServers", getTurnServers(true))
							);
					break;
				case "play":
					if (user != null) {
						user.play(_c, msg, createTestPipeline());
					}
					break;
			}
		} else {
			final Client c = (Client)_c;

			if (c == null || c.getRoomId() == null) {
				log.warn("Incoming message from invalid user");
				return;
			}
			log.debug("Incoming message from user with ID '{}': {}", c.getUserId(), msg);
			switch (cmdId) {
				case "toggleActivity":
					toggleActivity(c, Client.Activity.valueOf(msg.getString("activity")));
					break;
				case "broadcastStarted":
				{
					KStream stream = getByUid(c.getUid());
					if (stream == null) {
						KRoom room = getRoom(c.getRoomId());
						stream = room.join(this, c);
					}
					stream.startBroadcast(this, c, msg.getString("sdpOffer"));
				}
					break;
				case "onIceCandidate":
				{
					KStream sender = getByUid(msg.getString("uid"));
					if (sender != null) {
						JSONObject candidate = msg.getJSONObject("candidate");
						IceCandidate cand = new IceCandidate(
								candidate.getString("candidate")
								, candidate.getString("sdpMid")
								, candidate.getInt("sdpMLineIndex"));
						sender.addCandidate(cand, c.getUid());
					}
				}
					break;
				case "addListener":
				{
					KStream sender = getByUid(msg.getString("sender"));
					if (sender != null) {
						sender.addListener(this, c, msg.getString("sdpOffer"));
					}
				}
					break;
			}
		}
	}

	private static boolean isBroadcasting(final Client c) {
		return c.hasAnyActivity(Client.Activity.broadcastA, Client.Activity.broadcastV);
	}

	public void toggleActivity(Client c, Client.Activity a) {
		log.info("PARTICIPANT {}: trying to toggle activity {}", c, c.getRoomId());

		if (!activityAllowed(c, a, c.getRoom())) {
			if (a == Client.Activity.broadcastA || a == Client.Activity.broadcastAV) {
				c.allow(Room.Right.audio);
			}
			if (!c.getRoom().isAudioOnly() && (a == Client.Activity.broadcastV || a == Client.Activity.broadcastAV)) {
				c.allow(Room.Right.video);
			}
		}
		if (activityAllowed(c, a, c.getRoom())) {
			boolean wasBroadcasting = isBroadcasting(c);
			if (a == Client.Activity.broadcastA && !c.isMicEnabled()) {
				return;
			}
			if (a == Client.Activity.broadcastV && !c.isCamEnabled()) {
				return;
			}
			if (a == Client.Activity.broadcastAV && !c.isMicEnabled() && !c.isCamEnabled()) {
				return;
			}
			c.toggle(a);
			if (!isBroadcasting(c)) {
				//close
				KStream s = getByUid(c.getUid());
				if (s != null) {
					cm.update(c.removeStream(c.getUid()));
					s.stopBroadcast();
				}
				WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.rightUpdated, c.getUid()));
				//FIXME TODO update interview buttons
			} else if (!wasBroadcasting) {
				//join
				StreamDesc sd = new StreamDesc(c.getSid(), c.getUid(), StreamDesc.Type.broadcast);
				sd.setWidth(c.getWidth());
				sd.setHeight(c.getHeight());
				cm.update(c.addStream(sd));
				log.debug("User {}: has started broadcast", sd.getUid());
				sendClient(sd.getSid(), newKurentoMsg()
						.put("id", "broadcast")
						.put("uid", sd.getUid())
						.put("stream", new JSONObject(sd))
						.put("iceServers", getTurnServers(false))
						.put("client", c.toJson(true).put("type", "room"))); // FIXME TODO add multi-stream support
				//FIXME TODO update interview buttons
			} else {
				//constraints were changed
				WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.rightUpdated, c.getUid()));
			}
		}
	}

	public void leaveRoom(Client c) {
		remove(c);
		WebSocketHelper.sendAll(newKurentoMsg()
				.put("id", "broadcastStopped")
				.put("uid", c.getUid())
				.toString()
			);
	}

	Client getBySid(String sid) {
		return cm.getBySid(sid);
	}

	public void sendClient(String sid, JSONObject msg) {
		WebSocketHelper.sendClient(cm.getBySid(sid), msg);
	}

	public static void sendError(IWsClient c, String msg) {
		WebSocketHelper.sendClient(c, newKurentoMsg()
				.put("id", "error")
				.put("message", msg));
	}

	public void remove(IWsClient _c) {
		if (_c == null) {
			return;
		}
		final String uid = _c.getUid();
		final boolean test = !(_c instanceof Client);
		IKStream u = test ? getTestByUid(uid) : getByUid(uid);
		if (u != null) {
			u.release();
			if (test) {
				testsByUid.remove(uid);
			} else {
				usersByUid.remove(uid);
			}
		}
		if (test) {
			return;
		}
		Client c = (Client)_c;
		if (c.getRoomId() != null) {
			KRoom room = rooms.get(c.getRoomId());
			if (room != null) {
				room.leave(c);
			}
		}
	}

	public KRoom getRoom(Long roomId) {
		log.debug("Searching for room {}", roomId);
		KRoom room = rooms.get(roomId);

		if (room == null) {
			log.debug("Room {} does not exist. Will create now!", roomId);
			Transaction t = client.beginTransaction();
			MediaPipeline pipe = client.createMediaPipeline(t);
			pipe.addTag(t, TAG_KUID, kuid);
			pipe.addTag(t, TAG_ROOM, String.valueOf(roomId));
			t.commit();
			room = new KRoom(roomId, pipe);
			rooms.put(roomId, room);
		}
		log.debug("Room {} found!", roomId);
		return room;
	}

	private KStream getByUid(String uid) {
		return uid == null ? null : usersByUid.get(uid);
	}

	private KTestStream getTestByUid(String uid) {
		return uid == null ? null : testsByUid.get(uid);
	}

	static JSONObject newKurentoMsg() {
		return new JSONObject().put("type", KURENTO_TYPE);
	}

	static JSONObject newTestKurentoMsg() {
		return newKurentoMsg().put(TAG_MODE, MODE_TEST);
	}

	public static boolean activityAllowed(Client c, Client.Activity a, Room room) {
		boolean r = false;
		switch (a) {
			case broadcastA:
				r = c.hasRight(Right.audio);
				break;
			case broadcastV:
				r = !room.isAudioOnly() && c.hasRight(Right.video);
				break;
			case broadcastAV:
				r = !room.isAudioOnly() && c.hasRight(Right.audio) && c.hasRight(Right.video);
				break;
			default:
				break;
		}
		return r;
	}

	public JSONArray getTurnServers() {
		return getTurnServers(false);
	}

	private JSONArray getTurnServers(final boolean test) {
		JSONArray arr = new JSONArray();
		if (!Strings.isEmpty(turnUrl)) {
			try {
				JSONObject turn = new JSONObject();
				if ("rest".equalsIgnoreCase(turnMode)) {
					Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
					mac.init(new SecretKeySpec(turnSecret.getBytes(), HMAC_SHA1_ALGORITHM));
					StringBuilder user = new StringBuilder()
							.append((test ? 60 : turnTtl * 60) + System.currentTimeMillis() / 1000L);
					if (!Strings.isEmpty(turnUser)) {
						user.append(':').append(turnUser);
					}
					turn.put("username", user)
						.put("credential", Base64.getEncoder().encodeToString(mac.doFinal(user.toString().getBytes())));
				} else {
					turn.put("username", turnUser)
						.put("credential", turnSecret);
				}
				turn.put("url", String.format("turn:%s", turnUrl));
				arr.put(turn);
			} catch (NoSuchAlgorithmException|InvalidKeyException e) {
				log.error("Unexpected error while creating turn", e);
			}
		}
		return arr;
	}

	public void setCheckTimeout(long checkTimeout) {
		this.checkTimeout = checkTimeout;
	}

	public void setKurentoWsUrl(String kurentoWsUrl) {
		this.kurentoWsUrl = kurentoWsUrl;
	}

	public void setTurnUrl(String turnUrl) {
		this.turnUrl = turnUrl;
	}

	public void setTurnUser(String turnUser) {
		this.turnUser = turnUser;
	}

	public void setTurnSecret(String turnSecret) {
		this.turnSecret = turnSecret;
	}

	public void setTurnMode(String turnMode) {
		this.turnMode = turnMode;
	}

	public void setTurnTtl(int turnTtl) {
		this.turnTtl = turnTtl;
	}

	private class KWatchDog implements EventListener<ObjectCreatedEvent> {
		private ScheduledExecutorService scheduler;

		public KWatchDog() {
			scheduler = Executors.newScheduledThreadPool(10);
		}

		@Override
		public void onEvent(ObjectCreatedEvent evt) {
			log.debug("Kurento::ObjectCreated -> {}", evt.getObject());
			if (evt.getObject() instanceof MediaPipeline) {
				// room created
				final String roid = evt.getObject().getId();
				scheduler.schedule(() -> {
					if (client == null) {
						return;
					}
					// still alive
					MediaPipeline pipe = client.getById(roid, MediaPipeline.class);
					Map<String, String> tags = tagsAsMap(pipe);
					try {
						if (validTestPipeline(tags)) {
							return;
						}
						if (kuid.equals(tags.get(TAG_KUID))) {
							KRoom r = rooms.get(Long.valueOf(tags.get(TAG_ROOM)));
							if (r.getPipelineId().equals(pipe.getId())) {
								return;
							} else if (r != null) {
								rooms.remove(r.getRoomId());
								r.close();
							}
						}
					} catch(Exception e) {
						//no-op, connect will be dropped
					}
					log.warn("Invalid MediaPipeline {} detected, will be dropped, tags: {}", pipe.getId(), tags);
					pipe.release();
				}, checkTimeout, MILLISECONDS);
			} else if (evt.getObject() instanceof Endpoint) {
				// endpoint created
				Endpoint _point = (Endpoint)evt.getObject();
				final String eoid = _point.getId();
				Class<? extends Endpoint> _clazz = null;
				if (_point instanceof WebRtcEndpoint) {
					_clazz = WebRtcEndpoint.class;
				} else if (_point instanceof RecorderEndpoint) {
					_clazz = RecorderEndpoint.class;
				} else if (_point instanceof PlayerEndpoint) {
					_clazz = PlayerEndpoint.class;
				}
				final Class<? extends Endpoint> clazz = _clazz;
				scheduler.schedule(() -> {
					if (client == null || clazz == null) {
						return;
					}
					// still alive
					Endpoint point = client.getById(eoid, clazz);
					if (validTestPipeline(point.getMediaPipeline())) {
						return;
					}
					Map<String, String> tags = tagsAsMap(point);
					KStream stream = getByUid(tags.get("outUid"));
					if (stream != null && stream.contains(tags.get("uid"))) {
						return;
					}
					log.warn("Invalid Endpoint {} detected, will be dropped", point.getId());
					point.release();
				}, checkTimeout, MILLISECONDS);
			}
		}

		private boolean validTestPipeline(MediaPipeline pipeline) {
			return validTestPipeline(tagsAsMap(pipeline));
		}

		private boolean validTestPipeline(Map<String, String> tags) {
			return kuid.equals(tags.get(TAG_KUID)) && MODE_TEST.equals(tags.get(TAG_MODE)) && MODE_TEST.equals(tags.get(TAG_ROOM));
		}
	}
}
